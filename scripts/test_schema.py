"""Run the actual Store.java schema against SQLite, without an emulator."""
import json, pathlib, re, sqlite3

root = pathlib.Path(__file__).resolve().parent.parent
source = (root / 'app/src/main/java/cn/chendian/app/Store.java').read_text(encoding='utf-8')
constants = {key: json.loads(value) for key, value in re.findall(r'static final String (\w+_SQL)=("[^"\n]*");', source)}
def method_body(name):
    match = re.search(r'\b' + name + r'\([^)]*\)\s*\{', source)
    start = match.end()
    depth, pos = 1, start
    while depth:
        depth += (source[pos] == '{') - (source[pos] == '}')
        pos += 1
    return source[start:pos-1]

def sql_statements(body):
    for match in re.finditer(r'db\.execSQL\((.*?)\);|(roleIndexes|accountIndexes|migrateHierarchy|migrateAccounts)\(db\);', body):
        expression = match.group(1)
        if expression is None:
            yield from sql_statements(method_body(match.group(2)))
        elif expression.startswith('"'):
            yield json.loads(expression)
        else:
            key = expression.split('.')[0]
            sql = constants[key]
            if '.replace(' in expression:
                old, new = re.findall(r'"([^"]*)"', expression)
                sql = sql.replace(old, new)
            yield sql

schema = list(sql_statements(method_body('onCreate')))
assert len(schema) == 15, len(schema)
db = sqlite3.connect(':memory:')
db.execute('PRAGMA foreign_keys=ON')
for statement in schema:
    db.execute(statement)
db.execute("INSERT INTO accounts VALUES('a','QQ','123','主号')")
db.execute("INSERT INTO accounts VALUES('b','微信','wx123','小号')")
db.execute("INSERT INTO games VALUES('g','王者荣耀')")
db.execute("INSERT INTO regions(id,gameId,name) VALUES('z','g','一区')")
db.execute("INSERT INTO regions(id,gameId,name) VALUES('y','g','二区')")
db.execute("INSERT INTO roles VALUES('r','a','z','888','角色')")
db.execute("INSERT INTO roles VALUES('s','b','z','888','同区不同号')")
db.execute("INSERT INTO roles VALUES('t','a','y','888','同号不同区')")
checks = 0

def check(value, label):
    global checks
    checks += 1
    assert value, label

def rejected(sql):
    try:
        db.execute(sql)
    except sqlite3.IntegrityError:
        return True
    return False

check(rejected("INSERT INTO accounts VALUES('c','QQ','123','重复')"), 'unique account')
check(rejected("INSERT INTO accounts VALUES('c','unknown','456','错误类型')"), 'login types')
db.execute("INSERT INTO accounts VALUES('nick-qq','QQ','','昵称一')")
db.execute("INSERT INTO accounts VALUES('nick-qq2','QQ','','昵称二')")
db.execute("INSERT INTO accounts VALUES('nick-wx','微信','','昵称一')")
check(rejected("INSERT INTO accounts VALUES('bad','QQ','','')"), 'both account fields empty rejected')
check(rejected("INSERT INTO accounts VALUES('bad','QQ','   ','  ')"), 'whitespace-only account rejected')
check(rejected("INSERT INTO accounts VALUES('bad','QQ','','昵称一')"), 'nickname-only account dedup')
check(db.execute("SELECT count(*) FROM accounts WHERE identifier=''").fetchone()[0] == 3, 'distinct nickname-only accounts and login types')
check(rejected("INSERT INTO roles VALUES('u','a','z','888','重复')"), 'unique role in account and region')
check(rejected("INSERT INTO roles VALUES('u','missing','z','99','孤儿')"), 'no orphan role')
db.execute("INSERT INTO avatars VALUES('account-avatar','a',NULL,'account.img')")
db.execute("INSERT INTO avatars VALUES('game-avatar',NULL,'g','game.img')")
check(rejected("INSERT INTO avatars VALUES('duplicate','a',NULL,'another.img')"), 'only one avatar per account')
check(rejected("INSERT INTO avatars VALUES('invalid','a','g','invalid.img')"), 'avatar has exactly one owner')
check(rejected("INSERT INTO avatars VALUES('orphan','missing',NULL,'orphan.img')"), 'avatar requires valid owner')
db.execute("INSERT INTO regions VALUES('android','g','一区','安卓')")
db.execute("INSERT INTO regions VALUES('ios','g','一区','苹果')")
db.execute("INSERT INTO regions VALUES('cross','g','','全平台')")
db.execute("INSERT INTO roles VALUES('noid1','a','cross','','昵称一')")
db.execute("INSERT INTO roles VALUES('noid2','a','cross','','昵称二')")
check(rejected("INSERT INTO roles VALUES('dup','a','cross','','昵称一')"), 'nickname identity without role ID')
check(rejected("INSERT INTO regions VALUES('dup','g','一区','安卓')"), 'platform-region uniqueness')
check(db.execute("SELECT count(*) FROM regions WHERE name='一区'").fetchone()[0] == 3, 'same region across platforms')
for id_, role, start, created in [('old','r',100,300),('latest','r',200,250),('tie','r',200,350),('other','s',100,100)]:
    db.execute("INSERT INTO bans(id,roleId,start,end,permanent,confirmed,reason,note,remind,created) VALUES(?,?,?,9999,0,0,'','',1,?)", (id_, role, start, created))
rows = db.execute('SELECT id,roleId FROM bans ORDER BY start DESC,created DESC,id DESC').fetchall()
seen, current = set(), []
for id_, role in rows:
    if role not in seen:
        current.append(id_)
        seen.add(role)
check(current == ['tie','other'], 'current role selection including tied start times')
db.execute("INSERT INTO attachments VALUES('shot','tie','image.img','image/png')")
db.commit()
try:
    with db:
        db.execute('DELETE FROM accounts')
        db.execute("INSERT INTO roles VALUES('bad','missing','z','1','')")
except sqlite3.IntegrityError:
    pass
check(db.execute('SELECT count(*) FROM bans').fetchone()[0] == 4, 'failed replacement rollback preserves bans')
check(db.execute('SELECT count(*) FROM attachments').fetchone()[0] == 1, 'rollback preserves attachment metadata')
db.execute("DELETE FROM accounts WHERE id='a'")
check(db.execute('SELECT count(*) FROM bans').fetchone()[0] == 1, 'account cascade removes only its bans')
check(db.execute('SELECT count(*) FROM attachments').fetchone()[0] == 0, 'attachment cascade')
check(db.execute('SELECT count(*) FROM games').fetchone()[0] == 1, 'shared game retained')
check(db.execute('SELECT count(*) FROM avatars').fetchone()[0] == 1, 'account deletion retains only game avatar')
db.execute("DELETE FROM games WHERE id='g'")
check(db.execute('SELECT count(*) FROM roles').fetchone()[0] == 0, 'game cascade')
check(db.execute('SELECT count(*) FROM bans').fetchone()[0] == 0, 'shared game cascade removes remaining history')
check(db.execute('SELECT count(*) FROM avatars').fetchone()[0] == 0, 'game deletion removes game avatar')

# Replay the actual upgrade SQL against a v1 DB containing linked records.
legacy = sqlite3.connect(':memory:')
legacy.executescript((root / 'scripts/fixtures/schema-v1.sql').read_text(encoding='utf-8'))
legacy.execute("INSERT INTO accounts VALUES('a','QQ','123','旧账号')")
legacy.execute("INSERT INTO games VALUES('g','王者荣耀')")
legacy.execute("INSERT INTO regions VALUES('z','g','安卓一区')")
legacy.execute("INSERT INTO roles VALUES('r','a','z','888','')")
legacy.execute("INSERT INTO bans(id,roleId,start,end,permanent,confirmed,reason,note,remind,created) VALUES('b','r',100,9999,0,0,'','',1,100)")
legacy.execute("INSERT INTO attachments VALUES('shot','b','old.img','image/png')")
legacy.commit()
with legacy:
    for statement in sql_statements(method_body('onUpgrade')):
        legacy.execute(statement)
check(not legacy.execute('PRAGMA foreign_key_check').fetchall(), 'upgrade relationships valid')
check(legacy.execute('SELECT roleId,nickname FROM roles').fetchall() == [('888','')], 'legacy ID-only role preserved')
check(legacy.execute('SELECT name,platform FROM regions').fetchall() == [('安卓一区','')], 'legacy region preserved without guessing platform')
check(legacy.execute('SELECT count(*) FROM bans').fetchone()[0] == 1, 'upgrade preserves ban history')
check(legacy.execute('SELECT count(*) FROM attachments').fetchone()[0] == 1, 'upgrade preserves attachment metadata')
legacy.execute('PRAGMA foreign_keys=ON')
legacy.execute("DELETE FROM regions WHERE id='z'")
check(legacy.execute('SELECT count(*) FROM attachments').fetchone()[0] == 0, 'cascade still works after upgrade')

# A v2 database only needs the account migration; keep its existing platform hierarchy intact.
v2 = sqlite3.connect(':memory:')
v2.executescript((root / 'scripts/fixtures/schema-v1.sql').read_text(encoding='utf-8'))
for statement in sql_statements(method_body('migrateHierarchy')):
    v2.execute(statement)
v2.execute("INSERT INTO accounts VALUES('a','微信','wx-old','旧微信昵称')")
v2.execute("INSERT INTO games VALUES('g','三角洲')")
v2.execute("INSERT INTO regions VALUES('z','g','','全平台')")
v2.execute("INSERT INTO roles VALUES('r','a','z','','角色昵称')")
v2.execute("INSERT INTO bans(id,roleId,start,end,permanent,confirmed,reason,note,remind,created) VALUES('b','r',100,9999,0,0,'','',1,100)")
v2.commit()
with v2:
    for statement in sql_statements(method_body('migrateAccounts')):
        v2.execute(statement)
check(not v2.execute('PRAGMA foreign_key_check').fetchall(), 'v2 upgrade relationships valid')
check(v2.execute('SELECT identifier,name FROM accounts').fetchall() == [('wx-old','旧微信昵称')], 'v2 upgrade account preserved')
check(v2.execute('SELECT platform FROM regions').fetchall() == [('全平台',)], 'v2 upgrade platform preserved')
v2.execute("INSERT INTO accounts VALUES('new','微信','','只有昵称')")
check(v2.execute('SELECT count(*) FROM bans').fetchone()[0] == 1, 'v2 upgrade preserves history while allowing nickname-only accounts')
# A v3 upgrade only adds the avatar table and preserves all existing records.
v2.execute(constants['AVATARS_SQL'])
v2.execute("INSERT INTO avatars VALUES('avatar','a',NULL,'avatar.img')")
check(not v2.execute('PRAGMA foreign_key_check').fetchall(), 'v3 avatar migration retains relationships')
check(v2.execute('SELECT count(*) FROM bans').fetchone()[0] == 1, 'v3 avatar migration retains bans')
# Replay the v4 -> v5 addition against the previous production schema.
v4 = sqlite3.connect(':memory:')
v4.execute('PRAGMA foreign_keys=ON')
for statement in schema:
    if statement != constants['KIND_SQL']:
        v4.execute(statement)
v4.execute("INSERT INTO accounts VALUES('a','QQ','123','主号')")
v4.execute("INSERT INTO games VALUES('g','游戏')")
v4.execute("INSERT INTO regions VALUES('z','g','','全平台')")
v4.execute("INSERT INTO roles VALUES('r','a','z','','角色')")
v4.execute("INSERT INTO bans VALUES('ban','r',100,9999,0,0,'','',1,100)")
v4.execute(constants['KIND_SQL'])
check(v4.execute("SELECT kind FROM bans").fetchone()[0] == 'ban', 'v4 records remain bans')
v4.execute("INSERT INTO bans VALUES('cold','r',100,9999,0,0,'','',1,101,'cooldown')")
check(v4.execute("SELECT count(*) FROM bans WHERE kind='ban'").fetchone()[0] == 1, 'cooldown does not count as ban')
check(not v4.execute('PRAGMA foreign_key_check').fetchall(), 'v4 migration keeps relations')
v4.execute("DELETE FROM roles WHERE id='r'")
check(v4.execute('SELECT count(*) FROM bans').fetchone()[0] == 0, 'role deletion removes cooldowns too')
print(f'PASS {checks} SQLite hierarchy and transaction assertions')
