package cn.chendian.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import org.json.JSONObject;

public class ReminderReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c,Intent intent) {
        try(Store s=new Store(c)) {
            String id=intent.getStringExtra("id");JSONObject b=s.ban(id);int phase=intent.getIntExtra("phase",0);long end=intent.getLongExtra("end",0);
            if(b==null||b.optInt("permanent")==1||b.optLong("confirmed")>0||b.optLong("end")!=end||!s.currentIds().contains(id))return;
            boolean cooldown="cooldown".equals(b.optString("kind"));
            if((cooldown&&phase!=0)||System.currentTimeMillis()<end-(phase==1?86400000L:0))return;
            if((b.optInt("remind") & (phase==0?1:2))==0)return;
            String key=Reminders.delivered(id,phase,end);
            if(s.one("SELECT id FROM settings WHERE id=?",key)!=null)return;
            if(Build.VERSION.SDK_INT>=33 && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return;
            Reminders.channel(c);
            Intent open=new Intent(c,MainActivity.class).setData(Uri.parse("chendian://ban/"+id)).putExtra("banId",id).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent content=PendingIntent.getActivity(c,0,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            java.util.StringJoiner labels=new java.util.StringJoiner(" · ");
            for(String field:new String[]{"game","platform","region","nickname"}) {
                String value=b.optString(field);if(!value.trim().isEmpty())labels.add(value);
            }
            if(!b.optString("gameRoleId").trim().isEmpty())labels.add("ID "+b.optString("gameRoleId"));
            String text=labels.toString();
            Notification n=new Notification.Builder(c,cooldown?Reminders.COOLDOWN_CHANNEL:Reminders.CHANNEL).setSmallIcon(cn.chendian.app.R.drawable.ic_notification)
                .setContentTitle(cooldown?"冷号已完成":phase==1?"还有一天预计解封":"已到预计解封时间")
                .setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text+(cooldown?"\n设定的冷号期已结束，请结合实际账号情况评估风险。":"\n请进入游戏核实，实际解封后可在详情手动确认。")))
                .setContentIntent(content).setAutoCancel(true).build();
            c.getSystemService(NotificationManager.class).notify(id,1,n);s.setting(key,"1");
        }
    }
}
