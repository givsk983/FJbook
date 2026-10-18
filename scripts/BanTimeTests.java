import cn.chendian.app.BanTime;
import java.time.*;

public class BanTimeTests {
    static int count=0;
    static void check(boolean b,String label){count++;if(!b)throw new AssertionError(label);}
    public static void main(String[] args){
        long start=1700000000000L,end=BanTime.end(start,1,2,3);
        check(end-start==93780000L,"combined duration");
        check(BanTime.state(start,end,false,0,end-1).equals(BanTime.ACTIVE),"active until boundary");
        check(BanTime.state(start,end,false,0,end).equals(BanTime.DUE),"expiry is not confirmation");
        check(BanTime.state(start,end,false,end,end+1).equals(BanTime.CONFIRMED),"manual confirmation");
        check(BanTime.state(start,0,true,0,end).equals(BanTime.PERMANENT),"permanent");
        check(BanTime.state(start,0,true,end,end).equals(BanTime.CONFIRMED),"permanent manually lifted");
        check(!BanTime.soon(end,end),"expired is not soon");
        check(BanTime.soon(end,end-86400000L),"24h boundary");
        check(!BanTime.soon(end,end-86400001L),"outside 24h");
        ZoneId zone=ZoneId.of("Asia/Shanghai");long a=Instant.parse("2026-10-17T15:59:59Z").toEpochMilli(),b=Instant.parse("2026-10-17T16:00:00Z").toEpochMilli();
        check(!BanTime.today(a,b,zone),"local midnight date");
        check(BanTime.remaining(end,end-1).equals("0小时 0分 1秒"),"fractional second rounds up");
        check(BanTime.remaining(end,end-60000).equals("0小时 1分 0秒"),"minute boundary exact");
        check(BanTime.remaining(end,end-61000).equals("0小时 1分 1秒"),"seconds retained in countdown");
        check(BanTime.remaining(end,end).equals("等待确认解封"),"countdown expires at exact second");
        check(BanTime.end(start,0,0,0,1)==start+1000,"one second duration");
        check(BanTime.end(start,1,2,3,4)==start+93784000L,"mixed duration includes seconds");
        check(BanTime.end(start,0,0,0,90)==start+90000,"seconds normalize beyond sixty");
        boolean failed=false;try{BanTime.end(start,0,0,0);}catch(IllegalArgumentException e){failed=true;}check(failed,"zero duration rejected");
        failed=false;try{BanTime.end(start,-1,0,0);}catch(IllegalArgumentException e){failed=true;}check(failed,"negative duration rejected");
        failed=false;try{BanTime.end(start,Long.MAX_VALUE,0,0);}catch(ArithmeticException e){failed=true;}check(failed,"overflow rejected");
        failed=false;try{BanTime.end(start,0,0,0,-1);}catch(IllegalArgumentException e){failed=true;}check(failed,"negative seconds rejected");
        failed=false;try{BanTime.end(start,0,0,0,Long.MAX_VALUE);}catch(ArithmeticException e){failed=true;}check(failed,"seconds overflow rejected");
        System.out.println("PASS "+count+" time/status assertions");
    }
}
