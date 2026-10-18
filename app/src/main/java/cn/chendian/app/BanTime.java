package cn.chendian.app;

import java.time.*;

/** Time and status rules independent of Android; expiry is never an unlock confirmation. */
public final class BanTime {
    public static final String ACTIVE="封禁中", DUE="已到预计解封时间", CONFIRMED="已确认解封", PERMANENT="永久封禁";
    private BanTime() {}
    public static String state(long start, long end, boolean permanent, long confirmed, long now) {
        if (confirmed > 0) return CONFIRMED;
        if (permanent) return PERMANENT;
        return end <= now ? DUE : ACTIVE;
    }
    public static long end(long start, long days, long hours, long minutes) {
        return end(start,days,hours,minutes,0);
    }
    public static long end(long start, long days, long hours, long minutes, long seconds) {
        if (days<0 || hours<0 || minutes<0 || seconds<0) throw new IllegalArgumentException("时长不能为负数");
        long duration=Math.addExact(Math.addExact(Math.addExact(Math.multiplyExact(days,86400000L),Math.multiplyExact(hours,3600000L)),Math.multiplyExact(minutes,60000L)),Math.multiplyExact(seconds,1000L));
        if(duration<=0) throw new IllegalArgumentException("封禁时长必须大于零");
        return Math.addExact(start,duration);
    }
    public static boolean today(long millis, long now, ZoneId zone) {
        return Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().equals(Instant.ofEpochMilli(now).atZone(zone).toLocalDate());
    }
    public static boolean soon(long end, long now) { return end>now && end-now<=86400000L; }
    public static String remaining(long end, long now) {
        if(end<=now) return "等待确认解封";
        long delta=Math.subtractExact(end,now),s=delta/1000+(delta%1000==0?0:1);
        return (s>=86400 ? (s/86400)+"天 " : "")+(s%86400/3600)+"小时 "+(s%3600/60)+"分 "+(s%60)+"秒";
    }
}
