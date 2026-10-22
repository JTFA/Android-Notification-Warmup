package com.jtfa.notificationfixer.detect;

import android.app.Notification;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
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
 * <p>候选集合只有「现在有活动通知、且不是本应用与分组摘要」的那几个应用：行只能属于其中某个，
 * 在这一个小集合里比对，误认的面比拿全机应用去比小得多。
 *
 * <p>认不出就返回 {@code null} —— 调用方按「这一行不预热」处理（用户明确要的行为）。
 */
final class LockscreenMatch {

    /** 候选应用：包名 + 系统里显示的应用名。 */
    static final class Candidate {

        final String packageName;

        /** 应用名；读不到时是空串（那种情况只参与按包名认行）。 */
        final String label;

        Candidate(String packageName, String label) {
            this.packageName = packageName;
            this.label = label;
        }
    }

    private LockscreenMatch() {
    }

    /** 有活动通知的应用（最近发布在前、排除本应用与分组摘要），带上应用名。 */
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
            // 分组摘要点开只是展开分组，不会启动应用
            if ((sbn.getNotification().flags & Notification.FLAG_GROUP_SUMMARY) != 0) {
                continue;
            }
            String packageName = sbn.getPackageName();
            if (self.equals(packageName) || !seen.add(packageName)) {
                continue;
            }
            candidates.add(new Candidate(packageName, labelOf(packageManager, packageName)));
        }
        return candidates;
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
}
