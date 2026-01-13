package com.wechat.auto;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    
    private static final String TAG = "BootReceiver";
    
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Log.i(TAG, "设备启动，检查服务状态");
            
            // 检查无障碍服务是否启用
            if (WeChatAccessibilityService.isServiceEnabled(context)) {
                Log.i(TAG, "服务已启用");
            }
        }
    }
}
