package cn.chendian.app;

import android.content.*;
import android.database.sqlite.SQLiteDatabase;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;

/** Portable versioned ZIP; all validation is done before the database transaction. */
public final class Backup {
    static final long LIMIT=256L*1024*1024;
    public static File directory(Context c) { File f=new File(c.getFilesDir(),"attachments");f.mkdirs();return f; }
    public static void prune(Context c,Store s) {
        Set<String> live=new HashSet<>();for(JSONObject a:s.query("SELECT file FROM attachments"))live.add(a.optString("file"));
        for(JSONObject a:s.query("SELECT file FROM avatars"))live.add(a.optString("file"));
        File[] all=directory(c).listFiles();if(all!=null)for(File f:all)if(!live.contains(f.getName()))f.delete();
    }
    public static void write(Context c,Store s,OutputStream stream) throws Exception {
        JSONObject data=s.snapshot();
        byte[] manifest=data.toString(2).getBytes(StandardCharsets.UTF_8);long total=manifest.length;
        if(manifest.length>8*1024*1024)throw new IOException("备份清单超过 8 MB 限制");
        Set<String> checked=new HashSet<>();JSONArray attachments=media(data);
        for(int i=0;i<attachments.length();i++) {
            String name=attachments.getJSONObject(i).getString("file");if(!checked.add(name))continue;
            File f=new File(directory(c),name);if(!f.isFile())throw new IOException("附件缺失，无法创建完整备份");
            total+=f.length();if(total>LIMIT)throw new IOException("单份备份上限为 256 MB，请减少附件后再导出");
        }
        try(ZipOutputStream zip=new ZipOutputStream(new BufferedOutputStream(stream))) {
            zip.putNextEntry(new ZipEntry("manifest.json"));zip.write(manifest);zip.closeEntry();
            Set<String> done=new HashSet<>();JSONArray files=media(data);
            for(int i=0;i<files.length();i++) {
                String name=files.getJSONObject(i).getString("file");if(!done.add(name))continue;
                File f=new File(directory(c),name);if(!f.isFile())throw new IOException("附件缺失，无法创建完整备份");
                zip.putNextEntry(new ZipEntry("attachments/"+name));Files.copy(f.toPath(),zip);zip.closeEntry();
            }
        }
    }
    static JSONArray media(JSONObject data)throws JSONException {
        JSONArray result=new JSONArray();
        for(String table:new String[]{"attachments","avatars"}) {
            JSONArray rows=data.optJSONArray(table);if(rows!=null)for(int i=0;i<rows.length();i++)result.put(rows.getJSONObject(i));
        }
        return result;
    }
    static byte[] bytes(InputStream in,long max) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[16384];int n;
        while((n=in.read(buf))!=-1){if(out.size()+n>max)throw new IOException("备份超过安全大小限制");out.write(buf,0,n);}return out.toByteArray();
    }
    static String required(JSONObject o,String key) throws JSONException {
        String v=o.getString(key);if(v.trim().isEmpty()||v.length()>10000)throw new JSONException("无效字段："+key);return v;
    }
    static boolean safeName(String f) { return f.matches("[a-zA-Z0-9_-]{1,100}\\.(jpg|png|webp|gif|img)"); }
    static void validate(JSONObject root,Map<String,File> files) throws Exception {
        int version=root.getInt("version");
        if(!"chendian-backup".equals(root.getString("format"))||version<1||version>4)throw new IOException("不是支持的封禁手册备份（版本 1–4）");
        if(version<4)root.put("avatars",new JSONArray());
        Map<String,Set<String>> ids=new HashMap<>();
        for(String table:Store.TABLES) {
            JSONArray rows=root.getJSONArray(table);if(rows.length()>100000)throw new IOException("记录数量过大");
            Set<String> seen=new HashSet<>();ids.put(table,seen);
            for(int i=0;i<rows.length();i++) {
                JSONObject row=rows.getJSONObject(i);String id=required(row,"id");if(!table.equals("settings")&&!id.matches("[A-Za-z0-9_-]{1,100}"))throw new IOException("数据 ID 无效");if(!seen.add(id))throw new IOException("备份中存在重复 ID");
                switch(table) {
                    case "accounts":
                        if(!Arrays.asList("QQ","微信").contains(row.getString("type")))throw new IOException("未知登录类型");
                        String identifier=row.getString("identifier"),accountName=row.getString("name");
                        if(identifier.length()>10000||accountName.length()>10000||(identifier.trim().isEmpty()&&accountName.trim().isEmpty()))throw new IOException("账号和昵称至少填写一项");
                        if(version<3)required(row,"identifier");
                        break;
                    case "games": required(row,"name");break;
                    case "regions":
                        ref(ids,"games",row,"gameId");
                        if(version==1){required(row,"name");row.put("platform","");}
                        else if(row.getString("name").length()>10000||row.getString("platform").length()>10000||
                            (row.getString("name").trim().isEmpty()&&row.getString("platform").trim().isEmpty()))throw new IOException("平台或大区无效");
                        break;
                    case "roles":
                        ref(ids,"accounts",row,"accountId");ref(ids,"regions",row,"regionId");
                        String roleId=row.getString("roleId"),nickname=row.getString("nickname");
                        if(roleId.length()>10000||nickname.length()>10000||(roleId.trim().isEmpty()&&nickname.trim().isEmpty()))throw new IOException("角色昵称和 ID 不能同时为空");
                        if(version==1)required(row,"roleId");
                        break;
                    case "bans":
                        ref(ids,"roles",row,"roleId");long start=row.getLong("start"),end=row.getLong("end"),confirmed=row.getLong("confirmed");
                        int p=row.getInt("permanent"),m=row.getInt("remind");
                        if(start<=0||start>System.currentTimeMillis()+60000||confirmed<0||(confirmed>0&&confirmed<start)||row.getLong("created")<=0||(p!=0&&p!=1)||(p==0&&end<=start)||(p==1&&end!=0)||m<0||m>3||(p==1&&m!=0))throw new IOException("封禁时间或提醒配置无效");
                        row.getString("reason");row.getString("note");break;
                    case "attachments": ref(ids,"bans",row,"banId");String f=required(row,"file");
                        if(!safeName(f)||!files.containsKey(f)||!row.getString("mime").startsWith("image/"))throw new IOException("附件缺失或无效");break;
                    case "avatars":
                        boolean hasAccount=!row.isNull("accountId"),hasGame=!row.isNull("gameId");
                        if(hasAccount==hasGame)throw new IOException("头像归属无效");
                        if(hasAccount)ref(ids,"accounts",row,"accountId");else ref(ids,"games",row,"gameId");
                        String avatarFile=required(row,"file");
                        if(!safeName(avatarFile)||!files.containsKey(avatarFile))throw new IOException("头像文件缺失或无效");break;
                    case "settings": String value=row.getString("value");if(id.equals("defaultRemind")&&!value.matches("[0-3]"))throw new IOException("默认提醒配置无效");break;
                }
            }
        }
    }
    static void ref(Map<String,Set<String>> ids,String table,JSONObject row,String key)throws Exception {
        if(!ids.get(table).contains(row.getString(key)))throw new IOException("备份关系不完整："+key);
    }
    public static int restore(Context c,Store s,InputStream stream,boolean replace) throws Exception {
        File stage=new File(c.getCacheDir(),"restore-"+Store.uuid());if(!stage.mkdirs())throw new IOException("无法建立恢复临时目录");
        List<File> copied=new ArrayList<>();Map<String,File> files=new HashMap<>();Set<String> entries=new HashSet<>();JSONObject root=null;long total=0;
        try {
            try(ZipInputStream zip=new ZipInputStream(new BufferedInputStream(stream))) {
                ZipEntry e;while((e=zip.getNextEntry())!=null) {
                    String name=e.getName();if(!entries.add(name))throw new IOException("重复备份条目");
                    if(name.equals("manifest.json")) {
                        byte[] b=bytes(zip,8*1024*1024);total+=b.length;root=new JSONObject(new String(b,StandardCharsets.UTF_8));
                    } else if(name.startsWith("attachments/")&&safeName(name.substring(12))) {
                        String shortName=name.substring(12);File f=new File(stage,shortName);long count=0;
                        try(OutputStream out=new FileOutputStream(f)) {
                            byte[] b=new byte[16384];int n;while((n=zip.read(b))!=-1) { total+=n;count+=n;if(total>LIMIT||count>32*1024*1024L)throw new IOException("附件体积超限");out.write(b,0,n); }
                        }files.put(shortName,f);
                    } else throw new IOException("备份包含未知或不安全的路径");
                    if(total>LIMIT)throw new IOException("备份体积超限");
                }
            }
            if(root==null)throw new IOException("备份清单缺失");validate(root,files);
            SQLiteDatabase db=s.getWritableDatabase();int imported=0;db.beginTransaction();
            try {
                if(replace)for(int t=Store.TABLES.length-1;t>=0;t--)db.delete(Store.TABLES[t],null,null);
                Map<String,String> account=new HashMap<>(),game=new HashMap<>(),region=new HashMap<>(),role=new HashMap<>(),ban=new HashMap<>();
                for(String table:Store.TABLES) {
                    JSONArray rows=root.getJSONArray(table);
                    for(int i=0;i<rows.length();i++) {
                        JSONObject row=rows.getJSONObject(i);String source=row.getString("id"),target;
                        switch(table) {
                            case "accounts":
                                if(row.getString("identifier").isEmpty())target=entity(s,table,row,"type=? AND identifier='' AND name=?",row.getString("type"),row.getString("name"));
                                else target=entity(s,table,row,"type=? AND identifier=?",row.getString("type"),row.getString("identifier"));
                                account.put(source,target);break;
                            case "games": target=entity(s,table,row,"name=?",row.getString("name"));game.put(source,target);break;
                            case "regions": row.put("gameId",game.get(row.getString("gameId")));target=entity(s,table,row,"gameId=? AND platform=? AND name=?",row.getString("gameId"),row.getString("platform"),row.getString("name"));region.put(source,target);break;
                            case "roles":
                                row.put("accountId",account.get(row.getString("accountId"))).put("regionId",region.get(row.getString("regionId")));
                                if(row.getString("roleId").isEmpty())target=entity(s,table,row,"accountId=? AND regionId=? AND roleId='' AND nickname=?",row.getString("accountId"),row.getString("regionId"),row.getString("nickname"));
                                else target=entity(s,table,row,"accountId=? AND regionId=? AND roleId=?",row.getString("accountId"),row.getString("regionId"),row.getString("roleId"));
                                role.put(source,target);break;
                            case "bans":
                                String rid=role.get(row.getString("roleId"));
                                JSONObject old=s.one("SELECT id FROM bans WHERE roleId=? AND start=? AND end=? AND permanent=? AND reason=? AND note=?",rid,row.getString("start"),row.getString("end"),row.getString("permanent"),row.getString("reason"),row.getString("note"));
                                if(old!=null)target=old.getString("id");
                                else { target=s.one("SELECT id FROM bans WHERE id=?",source)==null?source:Store.uuid();row.put("id",target).put("roleId",rid);insert(db,table,row);imported++; }
                                ban.put(source,target);break;
                            case "attachments":
                                String bid=ban.get(row.getString("banId"));String oldFile=row.getString("file");
                                // Stable attachment id allows repeated merging without duplicating screenshots.
                                if(s.one("SELECT id FROM attachments WHERE id=? AND banId=?",source,bid)!=null)break;
                                String newFile=Store.uuid()+oldFile.substring(oldFile.lastIndexOf('.'));File dest=new File(directory(c),newFile);
                                Files.copy(files.get(oldFile).toPath(),dest.toPath());copied.add(dest);
                                row.put("id",s.one("SELECT id FROM attachments WHERE id=?",source)==null?source:Store.uuid()).put("banId",bid).put("file",newFile);insert(db,table,row);break;
                            case "avatars":
                                boolean accountAvatar=!row.isNull("accountId");
                                String ownerKey=accountAvatar?"accountId":"gameId";
                                String owner=(accountAvatar?account:game).get(row.getString(ownerKey));
                                if(s.one("SELECT id FROM avatars WHERE "+ownerKey+"=?",owner)!=null)break;
                                String oldAvatar=row.getString("file"),newAvatar=Store.uuid()+oldAvatar.substring(oldAvatar.lastIndexOf('.'));
                                File avatarDest=new File(directory(c),newAvatar);Files.copy(files.get(oldAvatar).toPath(),avatarDest.toPath());copied.add(avatarDest);
                                row.put("id",s.one("SELECT id FROM avatars WHERE id=?",source)==null?source:Store.uuid()).put(ownerKey,owner).put("file",newAvatar);insert(db,table,row);break;
                            case "settings":
                                if(replace||s.one("SELECT id FROM settings WHERE id=?",source)==null)s.setting(source,row.getString("value"));break;
                        }
                    }
                }
                if(s.one("SELECT id FROM settings WHERE id='defaultRemind'")==null)s.setting("defaultRemind","1");
                db.setTransactionSuccessful();
            } catch(Exception e) { for(File f:copied)f.delete();throw e; } finally { db.endTransaction(); }
            // Remove only orphaned private attachments, after successful commit.
            prune(c,s);
            return imported;
        } finally { File[] temp=stage.listFiles();if(temp!=null)for(File f:temp)f.delete();stage.delete(); }
    }
    static void insert(SQLiteDatabase db,String table,JSONObject row)throws JSONException {
        ContentValues values=new ContentValues();Iterator<String> keys=row.keys();while(keys.hasNext()){String key=keys.next();Object v=row.get(key);if(v==JSONObject.NULL)values.putNull(key);else if(v instanceof Number)values.put(key,((Number)v).longValue());else values.put(key,v.toString());}db.insertOrThrow(table,null,values);
    }
    static String entity(Store s,String table,JSONObject row,String where,String...args)throws JSONException {
        JSONObject old=s.one("SELECT id FROM "+table+" WHERE "+where,args);if(old!=null)return old.getString("id");
        String id=row.getString("id");if(s.one("SELECT id FROM "+table+" WHERE id=?",id)!=null)id=Store.uuid();
        row.put("id",id);insert(s.getWritableDatabase(),table,row);return id;
    }
}
