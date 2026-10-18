package cn.chendian.app;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import org.json.*;
import java.util.*;

/** All relationships use stable UUIDs, including across backup/restore. */
public class Store extends SQLiteOpenHelper {
    static final String[] TABLES={"accounts","games","regions","roles","bans","attachments","avatars","settings"};
    static final String AVATARS_SQL="CREATE TABLE avatars(id TEXT PRIMARY KEY,accountId TEXT UNIQUE REFERENCES accounts(id) ON DELETE CASCADE,gameId TEXT UNIQUE REFERENCES games(id) ON DELETE CASCADE,file TEXT NOT NULL,CHECK((accountId IS NOT NULL AND gameId IS NULL) OR (accountId IS NULL AND gameId IS NOT NULL)))";
    static final String ACCOUNTS_SQL="CREATE TABLE accounts(id TEXT PRIMARY KEY,type TEXT NOT NULL CHECK(type IN ('QQ','微信')),identifier TEXT NOT NULL DEFAULT '',name TEXT NOT NULL DEFAULT '',CHECK(length(trim(identifier))>0 OR length(trim(name))>0))";
    static final String REGIONS_SQL="CREATE TABLE regions(id TEXT PRIMARY KEY,gameId TEXT NOT NULL REFERENCES games(id) ON DELETE CASCADE,name TEXT NOT NULL,platform TEXT NOT NULL DEFAULT '',UNIQUE(gameId,platform,name))";
    static final String ROLES_SQL="CREATE TABLE roles(id TEXT PRIMARY KEY,accountId TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,regionId TEXT NOT NULL REFERENCES regions(id) ON DELETE CASCADE,roleId TEXT NOT NULL DEFAULT '',nickname TEXT NOT NULL DEFAULT '')";
    public Store(Context c) { super(c,"chendian.db",null,4); }
    Store(Context c,String name) { super(c,name,null,4); }
    // SQLite cannot rebuild referenced tables with foreign keys enabled inside an upgrade transaction.
    @Override public void onConfigure(SQLiteDatabase db) { db.setForeignKeyConstraintsEnabled(db.getVersion()==0||db.getVersion()>=3); }
    @Override public void onOpen(SQLiteDatabase db) { db.setForeignKeyConstraintsEnabled(true); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL(ACCOUNTS_SQL);
        accountIndexes(db);
        db.execSQL("CREATE TABLE games(id TEXT PRIMARY KEY,name TEXT NOT NULL UNIQUE)");
        db.execSQL(REGIONS_SQL);
        db.execSQL(ROLES_SQL);
        roleIndexes(db);
        db.execSQL("CREATE TABLE bans(id TEXT PRIMARY KEY,roleId TEXT NOT NULL REFERENCES roles(id) ON DELETE CASCADE,start INTEGER NOT NULL,end INTEGER NOT NULL DEFAULT 0,permanent INTEGER NOT NULL DEFAULT 0,confirmed INTEGER NOT NULL DEFAULT 0,reason TEXT NOT NULL DEFAULT '',note TEXT NOT NULL DEFAULT '',remind INTEGER NOT NULL DEFAULT 1,created INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX ban_role_time ON bans(roleId,start DESC,created DESC,id DESC)");
        db.execSQL("CREATE TABLE attachments(id TEXT PRIMARY KEY,banId TEXT NOT NULL REFERENCES bans(id) ON DELETE CASCADE,file TEXT NOT NULL,mime TEXT NOT NULL)");
        db.execSQL(AVATARS_SQL);
        db.execSQL("CREATE TABLE settings(id TEXT PRIMARY KEY,value TEXT NOT NULL)");
        db.execSQL("INSERT INTO settings VALUES('defaultRemind','1')");
    }
    private static void roleIndexes(SQLiteDatabase db) {
        db.execSQL("CREATE UNIQUE INDEX role_known_id ON roles(accountId,regionId,roleId) WHERE roleId<>''");
        db.execSQL("CREATE UNIQUE INDEX role_without_id ON roles(accountId,regionId,nickname) WHERE roleId=''");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int old,int next) {
        if(old<1||old>3||next!=4)throw new IllegalStateException("尚无此版本的数据迁移");
        if(old==1)migrateHierarchy(db);
        if(old<3)migrateAccounts(db);
        db.execSQL(AVATARS_SQL);
        try(Cursor errors=db.rawQuery("PRAGMA foreign_key_check",null)) {
            if(errors.moveToFirst())throw new IllegalStateException("数据迁移关系校验失败");
        }
    }
    private static void migrateHierarchy(SQLiteDatabase db) {
        db.execSQL(REGIONS_SQL.replace("CREATE TABLE regions(","CREATE TABLE regions_new("));
        db.execSQL("INSERT INTO regions_new(id,gameId,name,platform) SELECT id,gameId,name,'' FROM regions");
        db.execSQL(ROLES_SQL.replace("CREATE TABLE roles(","CREATE TABLE roles_new("));
        db.execSQL("INSERT INTO roles_new SELECT id,accountId,regionId,roleId,nickname FROM roles");
        db.execSQL("DROP TABLE roles");
        db.execSQL("DROP TABLE regions");
        db.execSQL("ALTER TABLE regions_new RENAME TO regions");
        db.execSQL("ALTER TABLE roles_new RENAME TO roles");
        roleIndexes(db);
    }
    private static void accountIndexes(SQLiteDatabase db) {
        db.execSQL("CREATE UNIQUE INDEX account_known_id ON accounts(type,identifier) WHERE identifier<>''");
        db.execSQL("CREATE UNIQUE INDEX account_without_id ON accounts(type,name) WHERE identifier=''");
    }
    private static void migrateAccounts(SQLiteDatabase db) {
        db.execSQL(ACCOUNTS_SQL.replace("CREATE TABLE accounts(","CREATE TABLE accounts_new("));
        db.execSQL("INSERT INTO accounts_new SELECT id,type,identifier,name FROM accounts");
        db.execSQL("DROP TABLE accounts");
        db.execSQL("ALTER TABLE accounts_new RENAME TO accounts");
        accountIndexes(db);
    }
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
        if(identifier.trim().isEmpty()&&name.trim().isEmpty())throw new IllegalArgumentException("账号和昵称至少填写一项");
        JSONObject old=findAccount(type,identifier,name);
        if(old!=null) return old.optString("id");
        String id=uuid(); getWritableDatabase().execSQL("INSERT INTO accounts VALUES(?,?,?,?)",new Object[]{id,type,identifier.trim(),name.trim()}); return id;
    }
    public JSONObject findAccount(String type,String identifier,String name) {
        return identifier.trim().isEmpty()
            ? one("SELECT * FROM accounts WHERE type=? AND identifier='' AND name=?",type,name.trim())
            : one("SELECT * FROM accounts WHERE type=? AND identifier=?",type,identifier.trim());
    }
    public String ensureGame(String name) {
        JSONObject old=one("SELECT * FROM games WHERE name=?",name.trim()); if(old!=null) return old.optString("id");
        String id=uuid();getWritableDatabase().execSQL("INSERT INTO games VALUES(?,?)",new Object[]{id,name.trim()});return id;
    }
    public String ensureRegion(String game,String name) {
        return ensureRegion(game,"",name);
    }
    public String ensureRegion(String game,String platform,String name) {
        JSONObject old=one("SELECT * FROM regions WHERE gameId=? AND platform=? AND name=?",game,platform.trim(),name.trim());if(old!=null)return old.optString("id");
        String id=uuid();getWritableDatabase().execSQL("INSERT INTO regions(id,gameId,name,platform) VALUES(?,?,?,?)",new Object[]{id,game,name.trim(),platform.trim()});return id;
    }
    public JSONObject findRole(String account,String region,String role,String nick) {
        return role.trim().isEmpty()
            ? one("SELECT * FROM roles WHERE accountId=? AND regionId=? AND roleId='' AND nickname=?",account,region,nick.trim())
            : one("SELECT * FROM roles WHERE accountId=? AND regionId=? AND roleId=?",account,region,role.trim());
    }
    public String ensureRole(String account,String region,String role,String nick) {
        JSONObject old=findRole(account,region,role,nick);
        if(old!=null) return old.optString("id");
        String id=uuid();getWritableDatabase().execSQL("INSERT INTO roles VALUES(?,?,?,?,?)",new Object[]{id,account,region,role.trim(),nick.trim()});return id;
    }
    static final String JOIN="SELECT b.*,a.type,a.identifier,a.name AS accountName,g.name AS game,z.name AS region,z.platform,r.roleId AS gameRoleId,r.nickname,r.accountId,r.regionId FROM bans b JOIN roles r ON r.id=b.roleId JOIN accounts a ON a.id=r.accountId JOIN regions z ON z.id=r.regionId JOIN games g ON g.id=z.gameId ";
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
        JSONObject root=new JSONObject().put("format","chendian-backup").put("version",4).put("exportedAt",System.currentTimeMillis());
        for(String t:TABLES)root.put(t,array(t));return root;
    }
}
