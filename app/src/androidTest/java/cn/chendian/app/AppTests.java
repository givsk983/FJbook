package cn.chendian.app;

import android.app.*;
import android.os.Bundle;
import android.content.*;
import android.database.sqlite.SQLiteDatabase;
import org.json.*;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;

/** On-device integration tests use a unique DB and private temporary attachment directory. */
public class AppTests extends Instrumentation {
    int assertions;
    void check(boolean value,String label){assertions++;if(!value)throw new AssertionError(label);}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle result=new Bundle();try{runTests();result.putString("stream","PASS "+assertions+" integration assertions\n");finish(Activity.RESULT_OK,result);}catch(Throwable e){result.putString("stream","FAIL "+e+"\n"+android.util.Log.getStackTraceString(e));finish(Activity.RESULT_CANCELED,result);}}
    void runTests() throws Exception {
        Context target=getTargetContext();String name="test-"+Store.uuid()+".db";File testRoot=new File(target.getCacheDir(),"test-"+Store.uuid());testRoot.mkdirs();
        Context isolated=new ContextWrapper(target){@Override public File getFilesDir(){File d=new File(testRoot,"files");d.mkdirs();return d;}@Override public File getCacheDir(){File d=new File(testRoot,"cache");d.mkdirs();return d;}};
        try(Store s=new Store(isolated,name)) {
            String a=s.ensureAccount("QQ","123456","主号"),g=s.ensureGame("王者荣耀"),z=s.ensureRegion(g,"安卓一区"),r=s.ensureRole(a,z,"888","角色一");
            check(s.ensureAccount("QQ","123456","other").equals(a),"account dedup");check(s.ensureRole(a,z,"888","").equals(r),"role dedup");
            String r2=s.ensureRole(a,z,"889","角色二");long now=System.currentTimeMillis()/60000*60000;
            insert(s,"b1",r,now-7200000,now+3600000,0,now-7200000);insert(s,"b2",r,now-3600000,now+7200000,0,now-3600000);insert(s,"b3",r2,now-3600000,0,1,now-3600000);
            check(s.currentIds().size()==2,"current role count excludes history");check(!s.currentIds().contains("b1")&&s.currentIds().contains("b2"),"latest by ban start");
            String file="fixture.img";byte[] content={1,2,3,4,5};Files.write(new File(Backup.directory(isolated),file).toPath(),content);s.getWritableDatabase().execSQL("INSERT INTO attachments VALUES('at1','b2',?,'image/png')",new Object[]{file});s.setting("defaultRemind","3");s.setting("sent:b2:0:"+(now+7200000),"1");
            ByteArrayOutputStream out=new ByteArrayOutputStream();Backup.write(isolated,s,out);byte[] archive=out.toByteArray();
            check(Backup.restore(isolated,s,new ByteArrayInputStream(archive),false)==0,"merge duplicate backup");check(s.bans().size()==3,"merge count stable");check(s.query("SELECT * FROM attachments").size()==1,"merge attachments dedup");
            check(Backup.restore(isolated,s,new ByteArrayInputStream(archive),true)==3,"replace restores all bans");check(s.defaultReminder()==3,"settings restored");check(s.currentIds().size()==2,"replace relations preserved");check(s.ensureAccount("QQ","123456","").equals(a),"replace preserves stable account id");
            JSONObject att=s.query("SELECT * FROM attachments").get(0);check(Arrays.equals(content,Files.readAllBytes(new File(Backup.directory(isolated),att.getString("file")).toPath())),"attachment bytes preserved");check(s.one("SELECT value FROM settings WHERE id=?","sent:b2:0:"+(now+7200000))!=null,"delivery settings preserved");
            int before=s.bans().size();boolean rejected=false;try{Backup.restore(isolated,s,new ByteArrayInputStream(zip("../escape",new byte[]{1})),true);}catch(Exception e){rejected=true;}check(rejected&&s.bans().size()==before,"unsafe path rejected without mutation");
            JSONObject invalid=s.snapshot();invalid.getJSONArray("bans").getJSONObject(0).put("roleId","missing");rejected=false;try{Backup.restore(isolated,s,new ByteArrayInputStream(zip("manifest.json",invalid.toString().getBytes("UTF-8"))),true);}catch(Exception e){rejected=true;}check(rejected&&s.bans().size()==before,"invalid relationships rejected");
            // Invalid uniqueness after an initial insert must roll back the whole replace.
            JSONObject broken=s.snapshot();broken.put("attachments",new JSONArray());JSONObject ban=broken.getJSONArray("bans").getJSONObject(0);ban.put("unknownColumn","bad");rejected=false;try{Backup.restore(isolated,s,new ByteArrayInputStream(zip("manifest.json",broken.toString().getBytes("UTF-8"))),true);}catch(Exception e){rejected=true;}check(rejected&&s.bans().size()==before,"transaction rollback");
            check(s.query("SELECT * FROM attachments").size()==1,"rollback retains attachments");
            JSONObject v1=s.snapshot();v1.put("version",1);v1.put("attachments",new JSONArray());
            for(int i=0;i<v1.getJSONArray("regions").length();i++)v1.getJSONArray("regions").getJSONObject(i).remove("platform");
            check(Backup.restore(isolated,s,new ByteArrayInputStream(zip("manifest.json",v1.toString().getBytes("UTF-8"))),true)==3,"v1 backup remains importable");
            check(s.ban("b2").getString("region").equals("安卓一区")&&s.ban("b2").getString("platform").isEmpty(),"v1 region preserved without platform inference");
            s.getWritableDatabase().delete("accounts",null,null);check(s.bans().isEmpty()&&s.query("SELECT * FROM roles").isEmpty()&&s.query("SELECT * FROM attachments").isEmpty(),"cascade delete full subtree");
            String player=s.ensureAccount("QQ","new-player","新账号");
            String cross=s.ensureRegion(g,"全平台",""),android=s.ensureRegion(g,"安卓","一区"),ios=s.ensureRegion(g,"苹果","一区");
            check(!android.equals(ios),"same named region has separate platform identity");
            String blank1=s.ensureRole(player,cross,"","跨端昵称"),blank2=s.ensureRole(player,cross,"","另一个昵称");
            check(!blank1.equals(blank2)&&s.ensureRole(player,cross,"","跨端昵称").equals(blank1),"optional ID uses nickname for identity");
            String androidRole=s.ensureRole(player,android,"same-id","安卓昵称"),iosRole=s.ensureRole(player,ios,"same-id","苹果昵称");
            check(!androidRole.equals(iosRole),"same ID on different platforms stays separate");
            insert(s,"cross-ban",blank1,now-3600000,now+3600000,0,now);insert(s,"android-ban",androidRole,now-3600000,now+3600000,0,now);insert(s,"ios-ban",iosRole,now-3600000,now+3600000,0,now);
            ByteArrayOutputStream platformOut=new ByteArrayOutputStream();Backup.write(isolated,s,platformOut);byte[] platformBackup=platformOut.toByteArray();
            check(s.snapshot().getInt("version")==3,"new backup version");
            check(Backup.restore(isolated,s,new ByteArrayInputStream(platformBackup),false)==0,"v2 merge matches blank ID by nickname");
            check(s.query("SELECT * FROM roles WHERE accountId=?",player).size()==4,"v2 merge keeps all distinct roles");
            check(Backup.restore(isolated,s,new ByteArrayInputStream(platformBackup),true)==3,"v2 replace restores platform records");
            check(s.ban("cross-ban").getString("region").isEmpty()&&s.ban("cross-ban").getString("platform").equals("全平台"),"cross-platform record needs no region");
            check(s.ban("android-ban").getString("platform").equals("安卓")&&s.ban("ios-ban").getString("platform").equals("苹果"),"backup keeps platform separation");
            String nickQQ=s.ensureAccount("QQ","","昵称一"),nickQQ2=s.ensureAccount("QQ","","昵称二"),nickWX=s.ensureAccount("微信","","昵称一");
            check(!nickQQ.equals(nickQQ2)&&!nickQQ.equals(nickWX),"distinct nickname-only accounts");
            check(s.ensureAccount("QQ","","昵称一").equals(nickQQ),"nickname-only account dedup");
            boolean emptyRejected=false;try{s.ensureAccount("QQ","  ","  ");}catch(IllegalArgumentException expected){emptyRejected=true;}
            check(emptyRejected,"account needs a number or nickname");
            String nicknameRole=s.ensureRole(nickQQ,cross,"","昵称账号的角色");insert(s,"nickname-ban",nicknameRole,now-3600000,now+3600000,0,now);
            ByteArrayOutputStream accountOut=new ByteArrayOutputStream();Backup.write(isolated,s,accountOut);byte[] accountBackup=accountOut.toByteArray();
            check(Backup.restore(isolated,s,new ByteArrayInputStream(accountBackup),false)==0,"nickname-only backup merge is idempotent");
            check(Backup.restore(isolated,s,new ByteArrayInputStream(accountBackup),true)==4,"nickname-only backup replacement");
            check(s.ban("nickname-ban").getString("accountName").equals("昵称一")&&s.ban("nickname-ban").getString("identifier").isEmpty(),"nickname-only account and ban relationship preserved");
            check(s.query("SELECT * FROM accounts WHERE identifier=''").size()==3,"backup keeps different nickname-only accounts");
        }finally{target.deleteDatabase(name);remove(testRoot);}
        migrationTest(target);
    }
    void migrationTest(Context target)throws Exception {
        String name="migration-"+Store.uuid()+".db";
        try {
            try(SQLiteDatabase legacy=target.openOrCreateDatabase(name,0,null)) {
                String schema;try(InputStream input=getContext().getAssets().open("schema-v1.sql")){schema=new String(Backup.bytes(input,1024*1024),"UTF-8");}
                for(String sql:schema.split(";"))if(!sql.trim().isEmpty())legacy.execSQL(sql);
                legacy.execSQL("INSERT INTO accounts VALUES('a','QQ','123','旧账号')");legacy.execSQL("INSERT INTO games VALUES('g','王者荣耀')");
                legacy.execSQL("INSERT INTO regions VALUES('z','g','安卓一区')");legacy.execSQL("INSERT INTO roles VALUES('r','a','z','888','')");
                legacy.execSQL("INSERT INTO bans VALUES('b','r',100,9999,0,0,'','',1,100)");legacy.execSQL("INSERT INTO attachments VALUES('shot','b','old.img','image/png')");legacy.setVersion(1);
            }
            try(Store upgraded=new Store(target,name)) {
                check(upgraded.getReadableDatabase().getVersion()==3,"Android database upgraded");
                check(upgraded.ban("b").getString("region").equals("安卓一区"),"Android upgrade preserves old region");
                check(upgraded.query("SELECT * FROM attachments").size()==1,"Android upgrade preserves attachment metadata");
                check(upgraded.query("PRAGMA foreign_key_check").isEmpty(),"Android upgrade relationships valid");
                check(upgraded.one("PRAGMA foreign_keys").optInt("foreign_keys")==1,"Android upgrade restores foreign keys");
                upgraded.getWritableDatabase().delete("regions","id=?",new String[]{"z"});
                check(upgraded.bans().isEmpty()&&upgraded.query("SELECT * FROM attachments").isEmpty(),"Android upgrade retains cascades");
            }
        } finally { target.deleteDatabase(name); }
    }
    static void insert(Store s,String id,String role,long start,long end,int permanent,long created){s.getWritableDatabase().execSQL("INSERT INTO bans VALUES(?,?,?,?,?,0,'','',0,?)",new Object[]{id,role,start,end,permanent,created});}
    static byte[] zip(String name,byte[] bytes)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();try(ZipOutputStream zip=new ZipOutputStream(out)){zip.putNextEntry(new ZipEntry(name));zip.write(bytes);zip.closeEntry();}return out.toByteArray();}
    static void remove(File f){File[] children=f.listFiles();if(children!=null)for(File c:children)remove(c);f.delete();}
}
