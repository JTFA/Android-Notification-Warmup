package com.jtfa.notificationfixer.detect;

import android.app.Notification;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把锁屏上的一行对应到「哪个应用」（0.7.34-M6 / 55）。
 *
 * <p>读数来自 {@link LockscreenWatch.Row} 那两条线索，判断规则按「先精确、再兜底、都不中就认不出」
 * 排：
 *
 * <ol>
 *   <li><b>行里的包名</b>：应用自己的通知布局渲染出来的节点带的是它的包名（见
 *       {@link LockscreenWatch} 的类注释）。行里出现的包名如果正好是某个候选应用的包名，就是它 ——
 *       这是精确对应，不需要猜。一行里出现<b>多个</b>候选包名（罕见）时不猜，交给调用方按
 *       「认不出」处理。</li>
 *   <li><b>行里的应用名</b>：与候选应用的应用名（{@link PackageManager#getApplicationLabel}，
 *       框架画在行上的就是它）比对。行里有一段文字与某个应用名<b>完全相等</b>最好；否则「行内文字
 *       含有应用名」（应用名至少 2 个字）也算，多个候选同时命中时取<b>更长的那个应用名</b>，
 *       一样长还不分彼此就不猜。</li>
 * </ol>
 *
 * <p>候选集合只有「现在有活动通知」的那几个应用（含分组摘要 —— 摘要行画的也是该应用，
 * 把它排除掉会让「锁屏上只有一条 [N条] 摘要」时整行认不出）；在这一个小集合里比对，误认的面
 * 比拿全机应用去比小得多。候选里确实没有时，再退一步：拿行内文字与「全机唯一的应用名」做
 * <b>完全相等</b>比对，只认唯一命中，仍然不猜。
 *
 * <p>认不出就返回 {@code null} —— 调用方按「这一行不预热」处理（用户明确要的行为）。
 */
final class LockscreenMatch {

    /** 候选应用：包名 + 系统里显示的应用名 + 是不是分组摘要。 */
    static final class Candidate {

        final String packageName;

        /** 应用名；读不到时是空串（那种情况只参与按包名认行）。 */
        final String label;

        /** 这条活动通知是不是分组摘要；只用于诊断日志。 */
        final boolean summary;

        Candidate(String packageName, String label, boolean summary) {
            this.packageName = packageName;
            this.label = label;
            this.summary = summary;
        }
    }

    /** 全机应用名 → 包名的索引，只在候选里认不出时兜底用；懒加载一次。 */
    private static volatile Map<String, String> sInstalledLabels;

    private LockscreenMatch() {
    }

    /** 有活动通知的应用（最近发布在前、含分组摘要），带上应用名。 */
    static List<Candidate> candidates(Context context) {
        List<Candidate> candidates = new ArrayList<>();
        List<StatusBarNotification> active = NotificationAccessService.activeNotifications();
        if (active.isEmpty()) {
            return candidates;
        }
        List<StatusBarNotification> sorted = new ArrayList<>(active);
        Collections.sort(sorted, new Comparator<StatusBarNotification>() {
            @Override
            public int compare(StatusBarNotification left, StatusBarNotification right) {
                return Long.compare(right.getPostTime(), left.getPostTime());
            }
        });

        PackageManager packageManager = context.getPackageManager();
        String self = context.getPackageName();
        Set<String> seen = new LinkedHashSet<>();
        for (StatusBarNotification sbn : sorted) {
            if (sbn == null || sbn.getPackageName() == null) {
                continue;
            }
            String packageName = sbn.getPackageName();
            if (self.equals(packageName) || !seen.add(packageName)) {
                continue;
            }
            boolean summary = (sbn.getNotification().flags
                    & Notification.FLAG_GROUP_SUMMARY) != 0;
            candidates.add(new Candidate(packageName,
                    labelOf(packageManager, packageName), summary));
        }
        return candidates;
    }

    /** 诊断日志用：把候选的「包名 / 应用名 / 摘要」串起来，最多列 12 个。 */
    static String describeCandidates(List<Candidate> candidates) {
        if (candidates.isEmpty()) {
            return "无";
        }
        StringBuilder text = new StringBuilder();
        int listed = 0;
        for (Candidate candidate : candidates) {
            if (listed >= 12) {
                text.append(" 等 ").append(candidates.size()).append(" 个");
                break;
            }
            if (listed > 0) {
                text.append("、");
            }
            text.append(candidate.packageName).append('(')
                    .append(candidate.label.isEmpty() ? "?" : candidate.label);
            if (candidate.summary) {
                text.append(",摘要");
            }
            text.append(')');
            listed++;
        }
        return text.toString();
    }

    /** 系统里那个应用名（框架画在行上的就是它）；读不到给空串。 */
    static String labelOf(PackageManager packageManager, String packageName) {
        try {
            ApplicationInfo info = packageManager.getApplicationInfo(packageName, 0);
            CharSequence label = packageManager.getApplicationLabel(info);
            return label == null ? "" : label.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 认一行：返回它对应的包名，认不出返回 {@code null}。
     *
     * @param why 非 null 时写一句「凭什么认成这个 / 为什么认不出」，进诊断日志
     */
    static String match(LockscreenWatch.Row row, List<Candidate> candidates, StringBuilder why) {
        // ① 行里的包名（精确）
        String byPackage = null;
        int packageHits = 0;
        for (Candidate candidate : candidates) {
            if (row.packages.contains(candidate.packageName)) {
                packageHits++;
                if (byPackage == null) {
                    byPackage = candidate.packageName;
                }
            }
        }
        if (packageHits == 1) {
            why.append("行里的包名正是它");
            return byPackage;
        }
        if (packageHits > 1) {
            why.append("行里出现多个候选应用的包名 ").append(packageHits).append(" 个，不猜");
            return null;
        }

        // ② 行里的应用名（兜底）
        String bestPackage = null;
        String bestLabel = null;
        int bestScore = 0;
        boolean tie = false;
        for (Candidate candidate : candidates) {
            if (candidate.label.isEmpty()) {
                continue;
            }
            int score = 0;
            for (String text : row.texts) {
                if (text.equals(candidate.label)) {
                    score = 2;
                    break;
                }
                if (candidate.label.length() >= 2 && text.contains(candidate.label)) {
                    score = Math.max(score, 1);
                }
            }
            if (score == 0) {
                continue;
            }
            if (score > bestScore
                    || (score == bestScore && bestLabel != null
                            && candidate.label.length() > bestLabel.length())) {
                bestPackage = candidate.packageName;
                bestLabel = candidate.label;
                bestScore = score;
                tie = false;
            } else if (score == bestScore && bestLabel != null
                    && candidate.label.length() == bestLabel.length()
                    && !candidate.packageName.equals(bestPackage)) {
                tie = true;
            }
        }
        if (bestPackage != null && !tie) {
            why.append(bestScore == 2 ? "行里的应用名「" : "行里的文字含应用名「")
                    .append(bestLabel).append("」");
            return bestPackage;
        }
        if (tie) {
            why.append("行里有多个应用名同时命中，不猜");
        } else if (row.packages.isEmpty()) {
            why.append("行里既没有来源应用的包名，也没有认得的应用名");
        } else {
            why.append("行里的包名不在候选应用里，也没认出应用名");
        }
        return null;
    }

    /**
     * 候选里认不出时的最后一步：拿行内文字与「全机唯一的应用名」做<b>完全相等</b>比对。
     *
     * <p>只用于锁屏行 —— ColorOS 这类 ROM 把整行画在 SystemUI 里，行内既没有来源包名，
     * 候选集合又可能因为分组摘要 / 活动通知快照而漏掉应用。完全相等 + 全机唯一才认，
     * 仍然不猜；读不到应用清单时返回 {@code null}，按「认不出」处理。
     */
    static String matchByInstalledLabel(LockscreenWatch.Row row, PackageManager packageManager,
                                        String self, StringBuilder why) {
        Map<String, String> index = installedLabels(packageManager, self);
        String hit = null;
        for (String text : row.texts) {
            String packageName = index.get(text);
            if (packageName == null) {
                continue;
            }
            if (hit != null && !hit.equals(packageName)) {
                appendReason(why, "行里有多个已安装应用名完全相等，不猜");
                return null;
            }
            hit = packageName;
        }
        if (hit != null) {
            appendReason(why, "行内文字与已安装应用名完全一致");
            return hit;
        }
        return null;
    }

    /** 把兜底结论接在已有原因后面，避免两句粘在一起。 */
    private static void appendReason(StringBuilder why, String reason) {
        if (why.length() > 0) {
            why.append("；");
        }
        why.append(reason);
    }

    /** 全机「唯一的应用名 → 包名」索引；懒加载一次，重名的一律不进。 */
    private static Map<String, String> installedLabels(PackageManager packageManager, String self) {
        Map<String, String> cached = sInstalledLabels;
        if (cached != null) {
            return cached;
        }
        Map<String, String> unique = new HashMap<>();
        Set<String> ambiguous = new HashSet<>();
        try {
            for (ApplicationInfo info : packageManager.getInstalledApplications(0)) {
                if (info == null || info.packageName == null || self.equals(info.packageName)) {
                    continue;
                }
                String label;
                try {
                    CharSequence text = packageManager.getApplicationLabel(info);
                    label = text == null ? "" : text.toString().trim();
                } catch (Exception e) {
                    continue;
                }
                if (label.length() < 2) {
                    continue;
                }
                String previous = unique.get(label);
                if (previous == null) {
                    unique.put(label, info.packageName);
                } else if (!previous.equals(info.packageName)) {
                    ambiguous.add(label);
                }
            }
            for (String label : ambiguous) {
                unique.remove(label);
            }
        } catch (Exception e) {
            // 读不到应用清单就当作没有兜底
        }
        sInstalledLabels = unique;
        return unique;
    }
}
