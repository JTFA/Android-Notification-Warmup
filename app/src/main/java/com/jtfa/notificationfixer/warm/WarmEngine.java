package com.jtfa.notificationfixer.warm;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.jtfa.notificationfixer.core.AppFilter;
import com.jtfa.notificationfixer.core.EventLog;
import com.jtfa.notificationfixer.core.ForegroundAppTracker;
import com.jtfa.notificationfixer.core.Prefs;
import com.jtfa.notificationfixer.detect.ImeWatch;
import com.jtfa.notificationfixer.detect.ScreenStateReceiver;
import com.jtfa.notificationfixer.detect.TriggerBus;
import com.jtfa.notificationfixer.detect.TriggerEvent;
import com.jtfa.notificationfixer.detect.TriggerType;
import com.jtfa.notificationfixer.probe.ComponentInventory;
import com.jtfa.notificationfixer.probe.WarmTarget;
import com.jtfa.notificationfixer.privileged.ActivityCheck;
import com.jtfa.notificationfixer.privileged.ProcessProbe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * 唤醒引擎：所有预热请求的唯一入口。
 *
 * <p>几条硬规则：
 *
 * <ul>
 *   <li><b>单线程串行。</b>预热会牵动 AMS，并发拉进程只会互相拖慢，也让时序无法解释。</li>
 *   <li><b>冷却窗口。</b>同一个应用在冷却窗口内只预热一次（默认 60 秒，可在设置里调，见
 *       {@code Prefs.getWarmCooldownSec()}）。免 root 下查不到别的进程状态，
 *       这里用「冷却 + 幂等预热」代替状态查询 —— 进程已存活时预热只是一次几毫秒的调用。
 *       有特权通道时（M4）改为在预热前后各探活一次（`pidof`）：先把「进程是不是这次拉起来的」
 *       变成日志里的证据，之后再考虑用它替掉这套近似判断。</li>
 *   <li><b>优先级抢占。</b>优先级 锁屏 &gt; 新通知 &gt; 面板。更高优先级到来时，
 *       还没执行的较低优先级任务会被丢掉，避免先跑去服务已经过时的场景。</li>
 *   <li><b>执行前再确认。</b>真正开跑之前检查目标是否已经在前台，是就跳过 ——
 *       用户已经点开通知了，不需要再预热。</li>
 *   <li><b>打字时不抢焦点（0.7.30-M6 / 51）。</b>全局新通知到达时，如果这个应用走的是「拉起 Activity」
 *       那一路、而且现在有输入法窗口可见，就按设置延后到输入法收起（最多等到用户设的上限）、
 *       或者干脆不拉。只有这一个组合需要这道闸门：另外三条路不启动界面组件，面板与锁屏发生时
 *       输入法本来就是收起的。见 {@link #holdForIme}。</li>
 * </ul>
 *
 * <p>一次触发能排上队的个数由用户设的「单次数量上限」定，谁先谁后先看用户设的
 * 每应用权重（高的先），同权重时保持检测层给的顺序。
 *
 * <p>「唤醒后台」那三条路只做 provider 引用获取 / bindService / 投递广播，绝不调用 startActivity 或
 * moveTaskToFront，所以不会影响用户从桌面图标进入应用时的页面。第 ④ 条路（借一个「不显示界面」
 * 的 Activity，见 {@code ActivityWarmStrategy}）会真的启动界面组件，因此它是<b>逐应用选出来</b>的
 * 一条路（名单页点「设置」选「拉起 Activity」）：选了它的应用只走这条路，没选的应用从不碰它。
 * 它的结果类型是「已下发」而不是「成功」。
 */
public final class WarmEngine implements TriggerBus.Listener {

    /**
     * 同应用冷却窗口与两次预热之间的串行间隔都由用户在设置里定，默认值见
     * {@code Prefs.WARM_COOLDOWN_SEC_DEFAULT}（60 秒）与 {@code Prefs.WARM_GAP_MS_DEFAULT}（120ms）。
     * 这里不再放常量：读的是 {@code Prefs.getWarmCooldownMs()} / {@code Prefs.getWarmGapMs()}，
     * 改完设置当场生效，不必重启应用。
     */

    /**
     * 复探延迟：给目标进程留出跑完 {@code Application.onCreate} 的时间。
     *
     * <p>provider 那条路是同步创建进程的（acquire 返回时进程已在），广播那条路是异步投递，
     * 所以统一等 1.5 秒再复探最省事，也够看出「起来了没有」。
     */
    public static final long AFTER_PROBE_DELAY_MS = 1_500L;

    /**
     * 亮屏补预热的复探延迟（5 秒）。
     *
     * <p>亮屏瞬间用户往往正在点锁屏通知；这时再跑 {@code pidof} 会和启动动画抢 CPU。
     * 推迟到 5 秒后只影响取证，不影响预热本身；手动测试仍按 1.5 秒。
     */
    public static final long AFTER_PROBE_DELAY_SCREEN_ON_MS = 5_000L;

    /** 面板场景的延迟：留出用户找通知的时间。个数上限见 {@code Prefs.getWarmMaxApps()}。 */
    public static final long SHADE_DELAY_MS = 200L;

    /** 锁屏场景已经在屏幕点亮后等过 400ms，这里不再延迟。 */
    public static final long LOCKSCREEN_DELAY_MS = 0L;

    /** 全局新通知场景立刻执行；候选永远只有发通知的那一个应用，所以数量上限对它没有影响。 */
    public static final long BANNER_DELAY_MS = 0L;

    private static final int PRIORITY_SHADE = 1;
    private static final int PRIORITY_BANNER = 2;
    private static final int PRIORITY_LOCKSCREEN = 3;
    private static final int PRIORITY_MANUAL = 100;

    /**
     * 为等输入法收起最多复看几次。
     *
     * <p>正常情况下次数由「延后上限 ÷ 复看间隔」决定（最短 100ms 间隔、最长 20 秒 ⇒ 最多 200 次），
     * 这里再放一个上限，防止设置里出现意外的组合把队列一直占着。
     */
    private static final int IME_DEFER_MAX_ATTEMPTS = 220;

    public interface Callback {
        void onResult(WarmResult result);
    }

    private static final class Task {
        final String packageName;
        final int priority;
        final long dueAtUptimeMs;
        final String source;
        final boolean manual;
        /** 手动测试时为 true：连「应用被停止 / 被停用」这类结论都忽略，真的去试一次。 */
        final boolean force;
        /**
         * 手动测试某个<b>具体</b> Activity 时用它，绕过「这个应用该走哪条路」的判定：
         * 名单页的 Activity 列表里每一行的「打开」都走这里（试的是那一行，不是当前选择）。
         */
        final ComponentName activityOverride;
        /**
         * 候选列表的「尝试后台打开」为 true：强制 noFront 测试，不读该应用自己的「尝试后台启动activity」开关；
         * 但启动决策与正式触发相同（支持才 launch-behind，否则按正式路径回退）。
         */
        final boolean activityLaunchBehind;
        final Callback callback;
        /** 这次是哪个触发点来的；手动测试为 null（输入法闸门只看自动触发，见 {@link #holdForIme}）。 */
        final TriggerType trigger;
        /** 这条任务第一次入队的时刻，用来算「为了等输入法收起已经等了多久」。 */
        final long queuedAtUptimeMs;
        /** 为了等输入法收起复看过几次（0 = 还没延后过）。 */
        final int deferAttempts;

        Task(String packageName, int priority, long dueAtUptimeMs,
             String source, boolean manual, boolean force, Callback callback) {
            this(packageName, priority, dueAtUptimeMs, source, manual, force, null, callback,
                    false, null, dueAtUptimeMs, 0);
        }

        Task(String packageName, int priority, long dueAtUptimeMs, String source,
             boolean manual, boolean force, ComponentName activityOverride, Callback callback) {
            this(packageName, priority, dueAtUptimeMs, source, manual, force, activityOverride,
                    callback, false, null, dueAtUptimeMs, 0);
        }

        Task(String packageName, int priority, long dueAtUptimeMs, String source,
             boolean manual, boolean force, ComponentName activityOverride,
             boolean activityLaunchBehind, Callback callback) {
            this(packageName, priority, dueAtUptimeMs, source, manual, force, activityOverride,
                    callback, activityLaunchBehind, null, dueAtUptimeMs, 0);
        }

        Task(String packageName, int priority, long dueAtUptimeMs, String source,
             boolean manual, boolean force, ComponentName activityOverride, Callback callback,
             boolean activityLaunchBehind, TriggerType trigger, long queuedAtUptimeMs,
             int deferAttempts) {
            this.packageName = packageName;
            this.priority = priority;
            this.dueAtUptimeMs = dueAtUptimeMs;
            this.source = source;
            this.manual = manual;
            this.force = force;
            this.activityOverride = activityOverride;
            this.activityLaunchBehind = activityLaunchBehind;
            this.callback = callback;
            this.trigger = trigger;
            this.queuedAtUptimeMs = queuedAtUptimeMs;
            this.deferAttempts = deferAttempts;
        }

        /** 为了等输入法收起而复看一次：同一个任务、往后推一个复看间隔。 */
        Task deferred(long nextDueAtUptimeMs) {
            return new Task(packageName, priority, nextDueAtUptimeMs, source, manual, force,
                    activityOverride, callback, activityLaunchBehind, trigger, queuedAtUptimeMs,
                    deferAttempts + 1);
        }
    }

    /**
     * 进程内单例。
     *
     * <p>这里持有的是 Application Context（不会随界面销毁而失效），
     * 与应用同生命周期，因此不存在泄漏到界面的问题。
     */
    @SuppressLint("StaticFieldLeak")
    private static volatile WarmEngine sInstance;

    private final Context mContext;
    private final String mSelfPackage;
    private final ProviderWarmStrategy mProviderStrategy;
    private final ServiceWarmStrategy mServiceStrategy;
    private final ReceiverWarmStrategy mReceiverStrategy;
    private final ActivityWarmStrategy mActivityStrategy;
    private final Handler mMain = new Handler(Looper.getMainLooper());

    private final Object mLock = new Object();
    private final LinkedList<Task> mPending = new LinkedList<>();
    private final LinkedHashSet<String> mInFlightPackages = new LinkedHashSet<>();
    private final Map<String, Long> mLastWarmUptimeMs = new HashMap<>();

    /** 最近一次预热结果，只给状态页展示用。 */
    private volatile WarmResult mLastResult;

    public static void install(Context context) {
        if (sInstance == null) {
            synchronized (WarmEngine.class) {
                if (sInstance == null) {
                    WarmEngine engine = new WarmEngine(context.getApplicationContext());
                    sInstance = engine;
                    TriggerBus.addListener(engine);
                    engine.startWorker();
                }
            }
        }
    }

    public static WarmEngine get() {
        return sInstance;
    }

    /** 最近一次预热结果，可能为 null。 */
    public WarmResult lastResult() {
        return mLastResult;
    }

    private WarmEngine(Context context) {
        mContext = context;
        mSelfPackage = context.getPackageName();
        mProviderStrategy = new ProviderWarmStrategy(context);
        mServiceStrategy = new ServiceWarmStrategy(context);
        mReceiverStrategy = new ReceiverWarmStrategy(context);
        mActivityStrategy = new ActivityWarmStrategy(context);
    }

    // ---------------------------------------------------------------- 触发入口

    @Override
    public void onTrigger(TriggerEvent event) {
        if (event == null || event.targetPackages.isEmpty()) {
            return;
        }
        if (!Prefs.isWarmEnabled()) {
            EventLog.get().log(EventLog.CAT_WARM,
                    "总开关已关闭，忽略 " + event.type.label());
            return;
        }
        if (event.type == TriggerType.SCREEN_ON_PENDING
                && (!Prefs.isTriggerLockscreen() || !Prefs.isScreenOffFastWarm())) {
            // 开关可能在排队后、执行前被关掉；这里再兜一次，保证关掉就是完全没有快速预热。
            EventLog.get().log(EventLog.CAT_WARM,
                    "锁屏点亮或亮屏补预热开关已关闭，忽略");
            return;
        }
        if (!ScreenStateReceiver.isScreenOn()) {
            EventLog.get().log(EventLog.CAT_WARM,
                    "屏幕未点亮，忽略 " + event.type.label());
            return;
        }

        final int priority;
        final long delayMs;
        switch (event.type) {
            case SHADE_OPENED:
            case SHADE_NOTIFICATION:
                // 面板已经展开时的新通知沿用面板场景的优先级和延迟；目标只有新通知应用。
                priority = PRIORITY_SHADE;
                delayMs = SHADE_DELAY_MS;
                break;
            case LOCKSCREEN_LIT:
            case LOCKSCREEN_NOTIFICATION:
            case SCREEN_ON_PENDING:
                // 锁屏停留时的新通知、锁屏点亮、亮屏补预热使用同一档优先级和延迟。
                priority = PRIORITY_LOCKSCREEN;
                delayMs = LOCKSCREEN_DELAY_MS;
                break;
            case BANNER_NOTIFICATION:
            default:
                // 全局新通知：内层开时只认横幅，内层关时是所有新通知。
                priority = PRIORITY_BANNER;
                delayMs = BANNER_DELAY_MS;
                break;
        }
        enqueue(event.targetPackages, priority, delayMs,
                Prefs.warmMaxAppsOrUnlimited(), event.type);
    }

    /**
     * 界面上的「试一次预热」：绕开冷却与名单，并且<b>强制尝试</b>。
     *
     * <p>强制尝试会忽略「已被强制停止 / 已被停用 / 已被挂起」这几个状态位，只要存在
     * 导出的主进程组件就真的去借一次。这么做的原因：这些状态位到底会不会让系统真的拒绝，
     * 是只能实测的问题 —— 厂商推送进来的通知正是来自这类应用，如果实测能过，
     * 免 root 的覆盖范围就比判定规则写的大得多。结论不写回缓存，一次失败不会被当成永久判定。
     */
    public void warmNowAsync(String packageName, Callback callback) {
        if (packageName == null || packageName.isEmpty()) {
            return;
        }
        // 应用可能刚被手动打开过一次，「不可预热」的旧结论要作废
        ComponentInventory.invalidate(packageName);
        Task task = new Task(packageName, PRIORITY_MANUAL, SystemClock.uptimeMillis(),
                "手动测试", true, true, callback);
        synchronized (mLock) {
            // 用户正在界面里操作，排队的触发任务已经过时
            mPending.clear();
            mPending.add(task);
            mLock.notifyAll();
        }
    }

    /**
     * 名单页 Activity 列表里每一行的「正常打开」：<b>就试这一个</b> Activity。
     *
     * <p>和 {@link #warmNowAsync} 的区别是它绕过「这个应用该走哪条路、该用哪个组件」的判定 ——
     * 用户点哪一行就试哪一行，用来当场验证「打开它到底有没有界面」。同样强制尝试、同样不等冷却。
     */
    public void warmActivityNowAsync(String packageName, ComponentName component, Callback callback) {
        if (packageName == null || packageName.isEmpty() || component == null) {
            return;
        }
        ComponentInventory.invalidate(packageName);
        Task task = new Task(packageName, PRIORITY_MANUAL, SystemClock.uptimeMillis(),
                "手动打开 Activity", true, true, component, callback);
        synchronized (mLock) {
            mPending.clear();
            mPending.add(task);
            mLock.notifyAll();
        }
    }

    /**
     * 候选列表的「尝试后台打开」：强制 noFront 试这一个 Activity，
     * <b>不</b>读该应用自己的「尝试后台启动activity」开关。
     *
     * <p>它仍然是手动测试，绕过「应用进程存在时不预热」；但启动决策与正式触发相同：
     * 支持 launch-behind 才后台打开，否则按正式路径回退（avoidMoveToFront / 普通启动）。
     */
    public void warmActivityBehindNowAsync(String packageName, ComponentName component,
                                           Callback callback) {
        if (packageName == null || packageName.isEmpty() || component == null) {
            return;
        }
        ComponentInventory.invalidate(packageName);
        Task task = new Task(packageName, PRIORITY_MANUAL, SystemClock.uptimeMillis(),
                "手动后台打开 Activity", true, true, component, true, callback);
        synchronized (mLock) {
            mPending.clear();
            mPending.add(task);
            mLock.notifyAll();
        }
    }

    /** 清空冷却记录，方便连续测试同一个应用。 */
    public void clearCooldowns() {
        synchronized (mLock) {
            mLastWarmUptimeMs.clear();
        }
        EventLog.get().log(EventLog.CAT_WARM, "已清除全部冷却记录");
    }

    // ---------------------------------------------------------------- 入队

    private void enqueue(List<String> packages, int priority, long delayMs,
                         int maxApps, TriggerType trigger) {
        long now = SystemClock.uptimeMillis();
        long dueAt = now + delayMs;
        String source = trigger.label();

        // 先按用户设的权重排一遍：一次触发能预热的个数受 maxApps 限制，
        // 谁排进这批就是这个顺序决定的。Collections.sort 是稳定排序 ⇒
        // 同权重时仍按调用方给的顺序（面板按通知远近、锁屏按屏幕上的顺序、横幅只有一个）。
        Map<String, Integer> weights = Prefs.getAppWeights();
        List<String> ordered = new ArrayList<>(packages);
        Collections.sort(ordered, (left, right) ->
                Integer.compare(weightOf(weights, right), weightOf(weights, left)));

        List<Task> batch = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        int filteredByList = 0;
        int skippedByCap = 0;

        for (String packageName : ordered) {
            if (packageName == null || packageName.isEmpty() || packageName.equals(mSelfPackage)) {
                continue;
            }
            if (!AppFilter.shouldWarm(packageName)) {
                filteredByList++;
                continue;
            }
            if (!seen.add(packageName)) {
                continue;
            }
            if (batch.size() >= maxApps) {
                skippedByCap++;
                continue;
            }
            batch.add(new Task(packageName, priority, dueAt, source, false, false, null, null,
                    false, trigger, dueAt, 0));
        }

        if (batch.isEmpty()) {
            EventLog.get().log(EventLog.CAT_WARM, "没有可预热的目标（来源：" + source
                    + "，候选 " + packages.size() + " 个，名单过滤掉 " + filteredByList + " 个）");
            return;
        }

        int preempted;
        int duplicatePending;
        synchronized (mLock) {
            preempted = 0;
            Iterator<Task> iterator = mPending.iterator();
            while (iterator.hasNext()) {
                if (iterator.next().priority < priority) {
                    iterator.remove();
                    preempted++;
                }
            }

            // 场景边界（面板展开/新通知、锁屏点亮探测/新通知）可能对同一个包各发一次。
            // 队列里或执行中已经有这个包时不再加第二条，避免冷却设为 0 时真的预热两次。
            LinkedHashSet<String> pendingPackages = new LinkedHashSet<>();
            for (Task pending : mPending) {
                pendingPackages.add(pending.packageName);
            }
            // 正在执行的那条也算在内：新触发不要在它跑完前再排一条。
            pendingPackages.addAll(mInFlightPackages);
            duplicatePending = 0;
            Iterator<Task> batchIterator = batch.iterator();
            while (batchIterator.hasNext()) {
                Task task = batchIterator.next();
                if (pendingPackages.contains(task.packageName)) {
                    batchIterator.remove();
                    duplicatePending++;
                    continue;
                }
                pendingPackages.add(task.packageName);
            }

            if (!batch.isEmpty()) {
                mPending.addAll(batch);
                mLock.notifyAll();
            }
        }

        if (batch.isEmpty()) {
            EventLog.get().log(EventLog.CAT_WARM,
                    "同一应用已在队列或执行中，跳过 " + duplicatePending + " 个（来源：" + source + "）");
            return;
        }

        StringBuilder names = new StringBuilder();
        for (Task task : batch) {
            if (names.length() > 0) {
                names.append(", ");
            }
            names.append(task.packageName);
            int weight = weightOf(weights, task.packageName);
            if (weight > 0) {
                names.append("(权重").append(weight).append(')');
            }
        }
        EventLog.get().log(EventLog.CAT_WARM,
                "排队 " + batch.size() + " 个（来源：" + source
                        + (delayMs > 0 ? "，延迟 " + delayMs + "ms" : "") + "）",
                "目标=" + names
                        + (skippedByCap > 0 ? "，超出单次上限未排队 " + skippedByCap + " 个" : "")
                        + (preempted > 0 ? "，抢占掉低优先级 " + preempted + " 个" : "")
                        + (duplicatePending > 0 ? "，同应用已在队列或执行中跳过 " + duplicatePending + " 个" : ""));
    }

    private static int weightOf(Map<String, Integer> weights, String packageName) {
        Integer weight = weights.get(packageName);
        return weight == null ? 0 : weight;
    }

    // ---------------------------------------------------------------- 工作线程

    private void startWorker() {
        Thread worker = new Thread(this::workerLoop, "nf-warm");
        worker.setDaemon(true);
        worker.start();
    }

    private void workerLoop() {
        while (true) {
            Task task;
            synchronized (mLock) {
                while (true) {
                    long now = SystemClock.uptimeMillis();
                    task = takeDue(now);
                    if (task != null) {
                        mInFlightPackages.add(task.packageName);
                        break;
                    }
                    long waitMs = nextWaitMs(now);
                    try {
                        if (waitMs <= 0L) {
                            mLock.wait();
                        } else {
                            mLock.wait(waitMs);
                        }
                    } catch (InterruptedException ignored) {
                        // 被中断就重新挑一次任务
                    }
                }
            }
            try {
                execute(task);
            } catch (Throwable t) {
                EventLog.get().log(EventLog.CAT_WARM, "预热异常 " + task.packageName + "：" + t);
            } finally {
                synchronized (mLock) {
                    mInFlightPackages.remove(task.packageName);
                }
            }
            SystemClock.sleep(Prefs.getWarmGapMs());
        }
    }

    /** 取出到点的任务：优先级高者先，同级按到点时间先。 */
    private Task takeDue(long now) {
        Task best = null;
        int bestIndex = -1;
        for (int i = 0; i < mPending.size(); i++) {
            Task candidate = mPending.get(i);
            if (candidate.dueAtUptimeMs > now) {
                continue;
            }
            if (best == null
                    || candidate.priority > best.priority
                    || (candidate.priority == best.priority
                    && candidate.dueAtUptimeMs < best.dueAtUptimeMs)) {
                best = candidate;
                bestIndex = i;
            }
        }
        if (best == null) {
            return null;
        }
        mPending.remove(bestIndex);
        return best;
    }

    /** 下一个任务还要等多久；返回 0 表示队列为空，可以无限等待。 */
    private long nextWaitMs(long now) {
        long earliest = Long.MAX_VALUE;
        for (Task task : mPending) {
            earliest = Math.min(earliest, task.dueAtUptimeMs);
        }
        if (earliest == Long.MAX_VALUE) {
            return 0L;
        }
        return Math.max(1L, earliest - now);
    }

    private void execute(Task task) {
        if (!task.manual) {
            if (!Prefs.isWarmEnabled()) {
                publish(WarmResult.skipped(task.packageName, "总开关已关闭"), task);
                return;
            }
            // 触发时亮屏、排到执行时已经熄屏：不再执行。锁屏探测等延迟任务也走这里。
            if (!ScreenStateReceiver.isScreenOn()) {
                publish(WarmResult.skipped(task.packageName, "屏幕未点亮"), task);
                return;
            }
            if (task.packageName.equals(ForegroundAppTracker.get())) {
                publish(WarmResult.skipped(task.packageName, "目标已在前台"), task);
                return;
            }
            long remaining = cooldownRemainingMs(task.packageName);
            if (remaining > 0L) {
                publish(WarmResult.skipped(task.packageName,
                        "冷却中，剩 " + ((remaining + 999L) / 1000L) + " 秒"), task);
                return;
            }
            // 输入法闸门（0.7.30-M6 / 51）：横幅 + 拉起 Activity 这一路在打字时会把输入法顶回去。
            // 读数与决策无条件写日志（见 logImeDecision），这一条只管「这次要不要真的拉」。
            if (holdForIme(task)) {
                return;
            }
        }

        // 探活证据（M4）：有特权通道就做，没有时整段跳过（0.7.40-M6 / 61 起不再有开关）。
        // 这一次探活会挡住工作线程几十毫秒 —— 换来的是「这次预热到底有没有把进程拉起来」
        // 这个唯一能核对的事实，值。
        boolean evidence = ProcessProbe.channelAvailable();
        ProcessProbe.Probe before = null;
        if (evidence) {
            before = ProcessProbe.probeBlocking(task.packageName);
            ProcessProbe.logBefore(task.packageName, before);
            // 探不到就别再复探一次了，那样只会多写一条「不可用」
            evidence = before.state != ProcessProbe.State.UNAVAILABLE;
        }

        // 探活说进程已经在跑时，自动触发是否跳过只看设置里的「应用进程存在时不预热」。
        // 0.7.55-M6 / 76 起第 ④ 条路（拉起 Activity）也服从这个开关，不再固定跳过；
        // 开关关着时即使进程在跑也真的预热（Activity 路会启动界面组件）。
        // 0.7.58-M6 / 79 的 launch-behind 不会绕过这道闸门：自动触发先探活，命中就跳过。
        // 手动「试一次」是用户点名要看结果的实验，任何一条路都不跳过。
        // 跳过**不记冷却** —— 「刚看了一眼」不是「刚预热过」。
        if (!task.manual && evidence && before != null
                && before.state == ProcessProbe.State.RUNNING
                && Prefs.isSkipIfRunning()) {
            publish(WarmResult.skipped(task.packageName,
                    "预热前进程已在运行（PID " + before.pid + "），按设置不预热"), task);
            return;
        }

        // 0.7.58-M6 / 79：开关打开后优先用公开的 makeTaskLaunchBehind，不再用探活决定要不要带。
        // processRunning 交给 ActivityWarmStrategy 做 launch-behind 回退；也交给 Provider / Service
        // 在失败时区分「进程没起来」和「进程已在运行、但这次幂等调用没完成」（0.7.70-M6 / 91）。
        // 注意：这道开关不绕过上面的“应用进程存在时不预热”；自动触发仍先探活，命中就跳过。
        boolean noFront = Prefs.isActivityAvoidMoveToFrontEnabled(task.packageName);
        boolean processRunning = evidence && before != null
                && before.state == ProcessProbe.State.RUNNING;
        WarmResult result = task.activityOverride != null
                ? warmExplicitActivity(task.packageName, task.activityOverride,
                        task.activityLaunchBehind, processRunning)
                : warmNow(task.packageName, task.force, task.manual, noFront, processRunning);
        // 探活已经确认进程在运行时，Provider / Service 这条路的失败只是「幂等调用没完成」，
        // 不是「预热失败」（进程本来就在）。只改读法，不改结果、冷却与重试规则；Activity 路
        // 的失败仍然是一次真正的启动失败。
        if (result.outcome == WarmResult.Outcome.FAILED && processRunning
                && isIdempotentBackgroundRoute(result)) {
            result = result.asIdempotentUnfinished();
        }
        // 失败也记冷却：不可预热的应用没必要每次触发都重试一遍
        markWarmed(task.packageName);

        if (task.manual) {
            // 手动测试：等进程把 Application.onCreate 跑完再复探，两段证据一起交回界面。
            // 队列已经为用户操作让过路了，这里慢 1.5 秒没关系。
            ProcessProbe.Probe after = null;
            if (evidence) {
                SystemClock.sleep(AFTER_PROBE_DELAY_MS);
                after = ProcessProbe.probeBlocking(task.packageName);
                ProcessProbe.logAfter(task.packageName, before, after,
                        "预热后 1.5 秒", result.describe());
            }
            // 探不了也如实交回去，界面上显示「未测」，不拿耗时假装
            result = result.withEvidence(new WarmEvidence(before, after));

            // Activity 路额外读三样原始事实（窗口有没有建 / 顶层是谁 / 后台启动限制拦没拦）。
            // 只在手动测试这条路上跑：三条 shell 只读命令，自动触发时不该多花这个钱。
            if (isActivityRoute(result)) {
                String readout = readActivityReadout(task.packageName, task.activityOverride);
                if (readout != null && !readout.isEmpty()) {
                    EventLog.get().log(EventLog.CAT_DIAG,
                            "Activity 路读数 " + task.packageName, readout);
                    result = result.withActivityReadout(readout);
                }
            }
        } else if (evidence) {
            // 自动路径：绝不为了取证拖住队列，复探交给特权线程延迟做。
            // 亮屏补预热推迟到 5 秒，避开用户点通知时的启动动画；其它触发仍是 1.5 秒。
            long afterProbeDelay = task.trigger == TriggerType.SCREEN_ON_PENDING
                    ? AFTER_PROBE_DELAY_SCREEN_ON_MS : AFTER_PROBE_DELAY_MS;
            ProcessProbe.scheduleAfterProbe(task.packageName, before,
                    result.describe(), afterProbeDelay);
        }

        publish(result, task);
    }

    /**
     * 全局新通知 + 「拉起 Activity」这一路的输入法闸门（0.7.30-M6 / 51）。
     *
     * <p>为什么只有这一个组合：只有新通知会在用户可能正在打字的时候到达，也只有「拉起 Activity」
     * 这条路会启动界面组件、把焦点抢走。面板与锁屏不设闸 —— 那两件事发生时输入法本来就是收起的；
     * 手动按钮（「试一次」/ 名单页每行的「打开」）也不设闸 —— 那是用户点名要看结果的实验。
     *
     * <p>三个档位见 {@code Prefs.BANNER_ACTIVITY_*}：一律不拉起（全局新通知下这条路整个不上场，与输入法
     * 读数无关）/ 输入法可见时不拉起（默认，等它收起、最多等到用户设的上限）/ 一律拉起（0.7.29 及
     * 以前的行为）。最后一档之外的读数读不到（无障碍没连上）时按<b>原行为</b>处理：照拉，并且在日志里
     * 写「未知」—— 绝不因为读不到就悄悄改成不拉。
     *
     * <p>延后不是丢：任务带着原来的优先级重新排回队列，复看间隔与上限都由设置决定；到点还没收起
     * 就放弃这一次（记一条「跳过」，不记冷却 —— 「这次没拉」不等于「刚预热过」）。
     *
     * @return true = 这次不要预热（已经延后、或已经放弃），execute 直接收工
     */
    private boolean holdForIme(Task task) {
        if (task.trigger != TriggerType.BANNER_NOTIFICATION) {
            return false;
        }
        if (!resolvesToActivity(task.packageName)) {
            return false;
        }
        int mode = Prefs.getBannerActivityMode();
        if (mode == Prefs.BANNER_ACTIVITY_ALWAYS) {
            // 这一档就是 0.7.29 及以前的行为：不看输入法，只把读数记下来备查
            logImeDecision(task, ImeWatch.state(), "拉起（「一律拉起」档位）");
            return false;
        }
        int state = ImeWatch.stateNow();
        if (mode == Prefs.BANNER_ACTIVITY_NEVER) {
            // 这一档是「横幅下这条路整个不上场」，与输入法可不可见无关 —— 读数只为日志留下
            logImeDecision(task, state, "跳过（「一律不拉起」档位）");
            publish(WarmResult.skipped(task.packageName,
                    "横幅：「一律不拉起」档位下这次不拉（这条路在横幅场景不上场）"), task);
            return true;
        }
        if (state != ImeWatch.VISIBLE) {
            // 这里也写一行：延后过之后是这一条把「等到了，现在拉」记下来（第一次决策与最终结论
            // 各一行，复看期间的中间读数不刷）。
            logImeDecision(task, state, "拉起");
            return false;
        }
        long maxMs = Prefs.getImeDeferMaxMs();
        long intervalMs = Prefs.getImePollMs();
        long waitedMs = SystemClock.uptimeMillis() - task.queuedAtUptimeMs;
        if (waitedMs + intervalMs > maxMs || task.deferAttempts >= IME_DEFER_MAX_ATTEMPTS) {
            logImeDecision(task, state, "放弃（已等 " + waitedMs + "ms，上限 " + maxMs + "ms）");
            publish(WarmResult.skipped(task.packageName,
                    "横幅：输入法一直可见，等了 " + waitedMs + "ms 后放弃（上限 " + maxMs + "ms）"),
                    task);
            return true;
        }
        synchronized (mLock) {
            mPending.add(task.deferred(SystemClock.uptimeMillis() + intervalMs));
            mLock.notifyAll();
        }
        if (task.deferAttempts == 0) {
            logImeDecision(task, state, "延后（每 " + intervalMs + "ms 复看，最多等到 " + maxMs + "ms）");
        }
        return true;
    }

    /**
     * 横幅闸门的原始读数 + 结论，写进「预热」日志。
     *
     * <p>不跟诊断开关走：这条是「为什么这次没拉 / 为什么延后」的唯一证据，与预热结果一样必须留下。
     * 复看期间不重复写（否则一次延后能刷出几十行），只在第一次决策与最终结论上各写一行。
     */
    private static void logImeDecision(Task task, int state, String action) {
        EventLog.get().log(EventLog.CAT_WARM,
                "横幅决策 " + task.packageName + "：输入法 " + ImeWatch.stateName(state)
                        + " → " + action,
                "读数 " + ImeWatch.ageMs() + "ms 前"
                        + (ImeWatch.imePackage() == null ? "" : "，输入法 " + ImeWatch.imePackage())
                        + "；窗口=" + ImeWatch.signature());
    }

    /** 这次是不是走的第 ④ 条路（借 Activity）。 */
    private static boolean isActivityRoute(WarmResult result) {
        return result != null && result.strategy != null && result.strategy.startsWith("Activity");
    }

    /**
     * 这次失败是不是「后台无可见副作用的幂等路」（Provider / Service）。
     *
     * <p>只有这两条路在进程已经存在时，失败可以读成「幂等调用没完成」；广播会执行对方代码，
     * Activity 会启动界面，它们的失败不该被这句话盖过去。
     */
    private static boolean isIdempotentBackgroundRoute(WarmResult result) {
        if (result == null || result.strategy == null) {
            return false;
        }
        return result.strategy.startsWith("Provider") || result.strategy.startsWith("Service");
    }

    /**
     * 这个包现在解析出来会不会走第 ④ 条路（还没预热就先问一次，用于「已经在跑就不启动」）。
     *
     * <p>以**解析出来的目标类型**为准，而不是以「用户为它选了哪种方式」为准：选了 Activity 却挑不出
     * 可用候选的应用，不该因为当前进程正好活着，就把「不可预热」的真实原因从日志里藏掉。
     * 结论命中缓存时几乎不要钱（只有可用的结论才进缓存）。
     */
    private boolean resolvesToActivity(String packageName) {
        try {
            WarmTarget target = ComponentInventory.resolve(mContext, packageName);
            return target.isAvailable() && target.kind == WarmTarget.Kind.ACTIVITY;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Activity 路的手动测试专用：读那三样原始事实。{@code override} 不为 null 时就读用户点的那
     * 一个组件（名单页每行的「打开」）；否则再解析一次拿组件（{@code forceResolve} 不写缓存，
     * 代价是一次包解析）。读不到就返回 null，界面上不显示这一段。
     */
    private String readActivityReadout(String packageName, ComponentName override) {
        try {
            if (override != null) {
                return ActivityCheck.read(override);
            }
            WarmTarget target = ComponentInventory.forceResolve(mContext, packageName);
            if (!target.isAvailable() || target.kind != WarmTarget.Kind.ACTIVITY) {
                return null;
            }
            return ActivityCheck.read(target.component);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 就试这一个 Activity（候选列表的两个测试按钮）：跳过「该走哪条路」的判定。
     *
     * <p>「正常打开」是用户要看界面，不套「尝试后台启动activity」那一项；
     * 「尝试后台打开」强制 noFront，但启动决策与正式触发相同（含回退）。
     */
    private WarmResult warmExplicitActivity(String packageName, ComponentName component,
                                            boolean launchBehind, boolean processRunning) {
        long start = SystemClock.uptimeMillis();
        return launchBehind
                ? mActivityStrategy.openBehindForInspection(packageName, component, start, processRunning)
                : mActivityStrategy.openForInspection(packageName, component, start);
    }

    private WarmResult warmNow(String packageName, boolean force, boolean manual, boolean noFront,
                               boolean processRunning) {
        long start = SystemClock.uptimeMillis();
        WarmTarget target = force
                ? ComponentInventory.forceResolve(mContext, packageName)
                : ComponentInventory.resolve(mContext, packageName);
        String label = force ? "强制尝试" : "不可预热";
        if (!target.isAvailable()) {
            long elapsed = SystemClock.uptimeMillis() - start;
            // 结论 + 原始状态一起进日志：判定有疑问时不用猜
            EventLog.get().log(EventLog.CAT_DIAG,
                    label + " " + packageName + "：" + target.reason, target.note);
            return WarmResult.failed(packageName, "-", elapsed,
                    (force ? "强制尝试：" : "") + target.reason);
        }
        if (target.note != null) {
            EventLog.get().log(EventLog.CAT_DIAG,
                    (force ? "强制尝试 " : "探测提醒 ") + packageName + "：" + target.note);
        }
        if (target.kind == WarmTarget.Kind.PROVIDER) {
            return mProviderStrategy.warm(packageName, target.authority, start, processRunning);
        }
        if (target.kind == WarmTarget.Kind.RECEIVER) {
            if (!Prefs.isReceiverWarmEnabled()) {
                return WarmResult.failed(packageName, "Receiver", 0L,
                        "广播接收器预热已在设置里关闭");
            }
            return mReceiverStrategy.warm(packageName, target.component, start);
        }
        if (target.kind == WarmTarget.Kind.ACTIVITY) {
            // 这条路的开关就是「这个应用被设成了「拉起 Activity」」（ComponentInventory 里判过），
            // 所以这里不再有第二道开关 —— 能走到这里就是用户为这个应用点过头的。
            return mActivityStrategy.warm(packageName, target.component, start, manual, noFront,
                    processRunning);
        }
        return mServiceStrategy.warm(packageName, target.component, start, processRunning);
    }

    private void publish(WarmResult result, Task task) {
        mLastResult = result;
        EventLog.get().log(EventLog.CAT_WARM, result.describe(),
                "来源=" + task.source + (task.force ? "（强制尝试）" : "")
                        + (result.launchMode == null ? "" : "；本次打开方式=" + result.launchMode));
        if (task.callback != null) {
            mMain.post(() -> task.callback.onResult(result));
        }
    }

    private long cooldownRemainingMs(String packageName) {
        final long cooldown = Prefs.getWarmCooldownMs();
        synchronized (mLock) {
            Long last = mLastWarmUptimeMs.get(packageName);
            if (last == null) {
                return 0L;
            }
            long elapsed = SystemClock.uptimeMillis() - last;
            return elapsed >= cooldown ? 0L : cooldown - elapsed;
        }
    }

    private void markWarmed(String packageName) {
        synchronized (mLock) {
            mLastWarmUptimeMs.put(packageName, SystemClock.uptimeMillis());
        }
    }
}
