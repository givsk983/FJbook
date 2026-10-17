package cn.chendian.app;
import android.content.*;
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c,Intent i) { try(Store s=new Store(c)) { Reminders.schedule(c,s); } }
}
