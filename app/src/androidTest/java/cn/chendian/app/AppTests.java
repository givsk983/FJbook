package cn.chendian.app;

import android.app.*;
import android.os.Bundle;
import android.content.*;
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
            s.getWritableDatabase().delete("accounts",null,null);check(s.bans().isEmpty()&&s.query("SELECT * FROM roles").isEmpty()&&s.query("SELECT * FROM attachments").isEmpty(),"cascade delete full subtree");
        }finally{target.deleteDatabase(name);remove(testRoot);}
    }
    static void insert(Store s,String id,String role,long start,long end,int permanent,long created){s.getWritableDatabase().execSQL("INSERT INTO bans VALUES(?,?,?,?,?,0,'','',0,?)",new Object[]{id,role,start,end,permanent,created});}
    static byte[] zip(String name,byte[] bytes)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();try(ZipOutputStream zip=new ZipOutputStream(out)){zip.putNextEntry(new ZipEntry(name));zip.write(bytes);zip.closeEntry();}return out.toByteArray();}
    static void remove(File f){File[] children=f.listFiles();if(children!=null)for(File c:children)remove(c);f.delete();}
}
