package cn.chendian.app;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.Build;
import org.json.JSONObject;
import java.util.*;

public final class Reminders {
    public static final String CHANNEL="ban_expiry";
    public static void channel(Context c) {
        NotificationChannel ch=new NotificationChannel(CHANNEL,"预计解封提醒",NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription("到期当天及提前一天的预计解封提醒");
        c.getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }
    static PendingIntent pending(Context c,String id,int phase,long end) {
        Intent i=new Intent(c,ReminderReceiver.class).setData(Uri.parse("chendian://reminder/"+id+"/"+phase)).putExtra("id",id).putExtra("phase",phase).putExtra("end",end);
        return PendingIntent.getBroadcast(c,0,i,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }
    static String delivered(String id,int phase,long end) { return "sent:"+id+":"+phase+":"+end; }
    public static void cancel(Context c,String id) {
        cancelAlarms(c,id);
        c.getSystemService(NotificationManager.class).cancel(id,1);
    }
    static void cancelAlarms(Context c,String id) {
        for(int phase=0;phase<2;phase++) {
            Intent i=new Intent(c,ReminderReceiver.class).setData(Uri.parse("chendian://reminder/"+id+"/"+phase));
            PendingIntent pi=PendingIntent.getBroadcast(c,0,i,PendingIntent.FLAG_NO_CREATE|PendingIntent.FLAG_IMMUTABLE);
            if(pi!=null)c.getSystemService(AlarmManager.class).cancel(pi);
        }
    }
    public static void cancelAll(Context c,Store s) { for(JSONObject b:s.bans())cancel(c,b.optString("id")); }
    public static void schedule(Context c,Store s) {
        channel(c);Set<String> current=s.currentIds();long now=System.currentTimeMillis();
        for(JSONObject b:s.bans()) {
            String id=b.optString("id");cancelAlarms(c,id);
            if(!current.contains(id)||b.optInt("permanent")==1||b.optLong("confirmed")>0){cancel(c,id);continue;}
            long end=b.optLong("end");int mode=b.optInt("remind");
            for(int phase=0;phase<2;phase++) {
                if((mode & (phase==0?1:2))==0)continue;
                long at=end-(phase==1?86400000L:0);
                if(phase==1 && at<=now)continue;
                if(s.one("SELECT id FROM settings WHERE id=?",delivered(id,phase,end))!=null)continue;
                c.getSystemService(AlarmManager.class).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,Math.max(now+1500,at),pending(c,id,phase,end));
            }
        }
    }
}
