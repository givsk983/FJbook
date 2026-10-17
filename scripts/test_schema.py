"""Run the actual Store.java schema against SQLite, without an emulator."""
import pathlib, re, sqlite3

root = pathlib.Path(__file__).resolve().parent.parent
source = (root / 'app/src/main/java/cn/chendian/app/Store.java').read_text(encoding='utf-8')
schema = re.findall(r'db\.execSQL\("((?:CREATE|INSERT INTO settings)[^"\n]*)"\);', source)
assert len(schema) == 9, len(schema)
db = sqlite3.connect(':memory:')
db.execute('PRAGMA foreign_keys=ON')
for statement in schema:
    db.execute(statement)
db.execute("INSERT INTO accounts VALUES('a','QQ','123','主号')")
db.execute("INSERT INTO accounts VALUES('b','微信','wx123','小号')")
db.execute("INSERT INTO games VALUES('g','王者荣耀')")
db.execute("INSERT INTO regions VALUES('z','g','一区')")
db.execute("INSERT INTO regions VALUES('y','g','二区')")
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
check(rejected("INSERT INTO roles VALUES('u','a','z','888','重复')"), 'unique role in account and region')
check(rejected("INSERT INTO roles VALUES('u','missing','z','99','孤儿')"), 'no orphan role')
for id_, role, start, created in [('old','r',100,300),('latest','r',200,250),('tie','r',200,350),('other','s',100,100)]:
    db.execute("INSERT INTO bans VALUES(?,?,?,9999,0,0,'','',1,?)", (id_, role, start, created))
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
db.execute("DELETE FROM games WHERE id='g'")
check(db.execute('SELECT count(*) FROM roles').fetchone()[0] == 0, 'game cascade')
check(db.execute('SELECT count(*) FROM bans').fetchone()[0] == 0, 'shared game cascade removes remaining history')
print(f'PASS {checks} SQLite hierarchy and transaction assertions')
