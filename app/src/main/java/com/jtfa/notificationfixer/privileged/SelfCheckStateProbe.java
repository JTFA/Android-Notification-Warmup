package com.jtfa.notificationfixer.privileged;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 特权自检 ③ 的靶子：一个只用来被「停用 → 启用」的组件。
 *
 * <p>为什么要一个单独的组件：0.5.0-M4 的自检拿本应用自己当靶子跑 {@code pm enable}，
 * 报了个假「可用」—— 应用本来就是启用状态，系统对新状态与当前一致的调用会短路掉
 * 权限检查，命令跑完了却什么都没证明（真机上第一次点「解冻」就翻车了）。
 * 现在改成一个状态真的会变的靶子：{@code pm disable} 它、再 {@code pm enable} 回来。
 *
 * <p>为什么不用现成的 {@link SelfCheckReceiver} 兼做靶子：万一「启用」那一步被系统拒了，
 * 组件会一直停用着，⑤ 的结论从此就不可信了。靶子单独一个，坏了也只影响 ③ 这一项。
 *
 * <p>它收不到任何广播（没有任何地方给它发），存在的意义就是「状态可以被改」。
 */
public final class SelfCheckStateProbe extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        // 故意留空
    }
}
