package cn.chendian.app;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import org.json.*;
import java.util.*;

/** All relationships use stable UUIDs, including across backup/restore. */
public class Store extends SQLiteOpenHelper {
    static final String[] TABLES={"accounts","games","regions","roles","bans","attachments","settings"};
    public Store(Context c) { super(c,"chendian.db",null,1); }
    Store(Context c,String name) { super(c,name,null,1); }
    @Override public void onConfigure(SQLiteDatabase db) { db.setForeignKeyConstraintsEnabled(true); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE accounts(id TEXT PRIMARY KEY,type TEXT NOT NULL CHECK(type IN ('QQ','微信')),identifier TEXT NOT NULL,name TEXT NOT NULL DEFAULT '',UNIQUE(type,identifier))");
        db.execSQL("CREATE TABLE games(id TEXT PRIMARY KEY,name TEXT NOT NULL UNIQUE)");
        db.execSQL("CREATE TABLE regions(id TEXT PRIMARY KEY,gameId TEXT NOT NULL REFERENCES games(id) ON DELETE CASCADE,name TEXT NOT NULL,UNIQUE(gameId,name))");
        db.execSQL("CREATE TABLE roles(id TEXT PRIMARY KEY,accountId TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,regionId TEXT NOT NULL REFERENCES regions(id) ON DELETE CASCADE,roleId TEXT NOT NULL,nickname TEXT NOT NULL DEFAULT '',UNIQUE(accountId,regionId,roleId))");
        db.execSQL("CREATE TABLE bans(id TEXT PRIMARY KEY,roleId TEXT NOT NULL REFERENCES roles(id) ON DELETE CASCADE,start INTEGER NOT NULL,end INTEGER NOT NULL DEFAULT 0,permanent INTEGER NOT NULL DEFAULT 0,confirmed INTEGER NOT NULL DEFAULT 0,reason TEXT NOT NULL DEFAULT '',note TEXT NOT NULL DEFAULT '',remind INTEGER NOT NULL DEFAULT 1,created INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX ban_role_time ON bans(roleId,start DESC,created DESC,id DESC)");
        db.execSQL("CREATE TABLE attachments(id TEXT PRIMARY KEY,banId TEXT NOT NULL REFERENCES bans(id) ON DELETE CASCADE,file TEXT NOT NULL,mime TEXT NOT NULL)");
        db.execSQL("CREATE TABLE settings(id TEXT PRIMARY KEY,value TEXT NOT NULL)");
        db.execSQL("INSERT INTO settings VALUES('defaultRemind','1')");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int old,int next) { throw new IllegalStateException("尚无此版本的数据迁移"); }
    public static String uuid() { return UUID.randomUUID().toString(); }
    public List<JSONObject> query(String sql,String...args) {
        List<JSONObject> rows=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery(sql,args)) {
            while(c.moveToNext()) {
                JSONObject o=new JSONObject();
                for(int i=0;i<c.getColumnCount();i++) try {
                    o.put(c.getColumnName(i),c.getType(i)==Cursor.FIELD_TYPE_INTEGER ? c.getLong(i) : c.getString(i));
                } catch(JSONException e) { throw new IllegalStateException(e); }
                rows.add(o);
            }
        }
        return rows;
    }
    public JSONObject one(String sql,String...args) { List<JSONObject> r=query(sql,args); return r.isEmpty()?null:r.get(0); }
    public String ensureAccount(String type,String identifier,String name) {
        JSONObject old=one("SELECT * FROM accounts WHERE type=? AND identifier=?",type,identifier.trim());
        if(old!=null) return old.optString("id");
        String id=uuid(); getWritableDatabase().execSQL("INSERT INTO accounts VALUES(?,?,?,?)",new Object[]{id,type,identifier.trim(),name.trim()}); return id;
    }
    public String ensureGame(String name) {
        JSONObject old=one("SELECT * FROM games WHERE name=?",name.trim()); if(old!=null) return old.optString("id");
        String id=uuid();getWritableDatabase().execSQL("INSERT INTO games VALUES(?,?)",new Object[]{id,name.trim()});return id;
    }
    public String ensureRegion(String game,String name) {
        JSONObject old=one("SELECT * FROM regions WHERE gameId=? AND name=?",game,name.trim());if(old!=null)return old.optString("id");
        String id=uuid();getWritableDatabase().execSQL("INSERT INTO regions VALUES(?,?,?)",new Object[]{id,game,name.trim()});return id;
    }
    public String ensureRole(String account,String region,String role,String nick) {
        JSONObject old=one("SELECT * FROM roles WHERE accountId=? AND regionId=? AND roleId=?",account,region,role.trim());
        if(old!=null) return old.optString("id");
        String id=uuid();getWritableDatabase().execSQL("INSERT INTO roles VALUES(?,?,?,?,?)",new Object[]{id,account,region,role.trim(),nick.trim()});return id;
    }
    static final String JOIN="SELECT b.*,a.type,a.identifier,a.name AS accountName,g.name AS game,z.name AS region,r.roleId AS gameRoleId,r.nickname,r.accountId,r.regionId FROM bans b JOIN roles r ON r.id=b.roleId JOIN accounts a ON a.id=r.accountId JOIN regions z ON z.id=r.regionId JOIN games g ON g.id=z.gameId ";
    public List<JSONObject> bans() { return query(JOIN+"ORDER BY b.start DESC,b.created DESC,b.id DESC"); }
    public JSONObject ban(String id) { return one(JOIN+"WHERE b.id=?",id); }
    public Set<String> currentIds() {
        Set<String> roles=new HashSet<>(),ids=new HashSet<>();
        for(JSONObject b:bans()) if(roles.add(b.optString("roleId"))) ids.add(b.optString("id"));
        return ids;
    }
    public int defaultReminder() { JSONObject r=one("SELECT value FROM settings WHERE id='defaultRemind'");return r==null?1:Integer.parseInt(r.optString("value")); }
    public void setting(String key,String value) { getWritableDatabase().execSQL("INSERT OR REPLACE INTO settings VALUES(?,?)",new Object[]{key,value}); }
    public JSONArray array(String table) { return new JSONArray(query("SELECT * FROM "+table)); }
    public JSONObject snapshot() throws JSONException {
        JSONObject root=new JSONObject().put("format","chendian-backup").put("version",1).put("exportedAt",System.currentTimeMillis());
        for(String t:TABLES)root.put(t,array(t));return root;
    }
}
