package cn.chendian.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.*;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.nio.file.Files;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    static final int BG=0xff0b0c10,GOLD=0xffd4b16a,WHITE=0xffeee9df,MUTED=0xffa29e97,GREEN=0xff91c8b3,RED=0xffd9938e;
    static final String[] REMIND={"不提醒","到期当天","提前一天","两者都提醒"};
    static final DateTimeFormatter FMT=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    Store store;LinearLayout root,body,nav,recordList,gameTabs;ScrollView scroll;
    HorizontalScrollView gameStrip;
    TextView[] overviewNumbers=new TextView[3];
    TextView overviewOptions;
    int tab=0,page=30;String detailId=null,roleHistory=null,accountOpen=null,search="",filter="全部状态",gameFilter="全部游戏",sort="即将解封";
    boolean history=false,busy=false,refreshing=false,replaceRestore=false;final ExecutorService io=Executors.newSingleThreadExecutor();final Handler handler=new Handler(Looper.getMainLooper());
    BanForm pendingForm;LinearLayout imagePreview;Uri previewUri;
    final Runnable tick=new Runnable(){public void run(){if(!isFinishing()&&!busy){if(detailId!=null)showDetail(detailId);else if(tab==0&&recordList!=null)renderRecords();}handler.postDelayed(this,60000);}};
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);store=new Store(this);JSONObject remembered=store.one("SELECT value FROM settings WHERE id=?","overviewGame");if(remembered!=null)gameFilter=remembered.optString("value","全部游戏");
        if(state!=null){tab=state.getInt("tab");detailId=state.getString("detail");accountOpen=state.getString("account");}
        render();Reminders.schedule(this,store);openIntent(getIntent());handler.postDelayed(tick,60000);
    }
    @Override protected void onSaveInstanceState(Bundle b){super.onSaveInstanceState(b);b.putInt("tab",tab);b.putString("detail",detailId);b.putString("account",accountOpen);}
    @Override protected void onNewIntent(Intent i){super.onNewIntent(i);setIntent(i);openIntent(i);}
    void openIntent(Intent i){String id=i.getStringExtra("banId");if(id!=null){if(store.ban(id)!=null)showDetail(id);else toast("这条记录已删除");}}
    @Override protected void onResume(){super.onResume();if(store!=null&&!busy)Reminders.schedule(this,store);}
    @Override protected void onDestroy(){handler.removeCallbacksAndMessages(null);io.shutdown();if(!busy)store.close();super.onDestroy();}
    int dp(float n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
    TextView text(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setLineSpacing(dp(2),1);return t;}
    LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(1);return l;}
    void gap(LinearLayout l,int h){View v=new View(this);l.addView(v,new LinearLayout.LayoutParams(1,dp(h)));}
    void label(LinearLayout l,String s){gap(l,14);l.addView(text(s,13,GOLD));gap(l,7);}
    Button button(String s,Runnable action){Button b=new Button(this);b.setText(s);b.setTextColor(GOLD);b.setTextSize(14);b.setAllCaps(false);b.setMinHeight(dp(44));b.setOnClickListener(v->action.run());return b;}
    GoldCard card(LinearLayout parent){GoldCard c=new GoldCard(this);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(13);parent.addView(c,lp);c.setAlpha(0f);c.animate().alpha(1f).setDuration(180).start();return c;}
    void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    void confirm(String title,String message,Runnable yes){new AlertDialog.Builder(this).setTitle(title).setMessage(message).setNegativeButton("取消",null).setPositiveButton("确认",(d,w)->yes.run()).show();}
    void render(){
        root=column();root.setBackgroundColor(BG);root.setOnApplyWindowInsetsListener((v,i)->{v.setPadding(0,i.getSystemWindowInsetTop(),0,i.getSystemWindowInsetBottom());return i;});
        scroll=new ScrollView(this);scroll.setFillViewport(true);body=column();body.setPadding(dp(20),dp(20),dp(20),dp(12));scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        nav=new LinearLayout(this);nav.setPadding(dp(8),dp(4),dp(8),dp(4));nav.setBackgroundColor(0xff121318);String[] names={"总览","账号","导入","设置"};
        for(int i=0;i<4;i++){final int index=i;TextView n=text(names[i],14,tab==i?GOLD:MUTED);n.setGravity(Gravity.CENTER);n.setPadding(0,dp(15),0,dp(15));n.setContentDescription(names[i]+"导航");n.setOnClickListener(v->{if(busy)return;tab=index;detailId=null;roleHistory=null;render();});nav.addView(n,new LinearLayout.LayoutParams(0,dp(52),1));}
        root.addView(nav);setContentView(root);
        if(detailId!=null){showDetailContent(detailId);return;}
        switch(tab){case 0:overview();break;case 1:accounts();break;case 2:imports();break;case 3:settings();break;}
    }
    void heading(String overline,String title,String subtitle){TextView t=text(title,28,WHITE);t.setTypeface(null,1);body.addView(t);if(!subtitle.isEmpty()){gap(body,8);body.addView(text(subtitle,13,MUTED));}gap(body,20);}
    GradientDrawable surface(int color,int stroke,float radius){
        GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));
        if(stroke!=0)d.setStroke(dp(1),stroke);return d;
    }
    TextView action(String title,String description,Runnable click){
        TextView t=text(title,14,GOLD);t.setGravity(Gravity.CENTER);t.setContentDescription(description);
        t.setBackground(surface(0xff19191d,0xff4b4030,13));t.setOnClickListener(v->click.run());return t;
    }
    void overview(){
        LinearLayout titleRow=new LinearLayout(this);titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=text("总览",28,WHITE);title.setTypeface(null,1);
        titleRow.addView(title,new LinearLayout.LayoutParams(0,dp(48),1));
        overviewOptions=action("筛选","筛选与排序",this::overviewFilter);
        titleRow.addView(overviewOptions,new LinearLayout.LayoutParams(dp(58),dp(38)));
        TextView add=action("＋","添加封禁记录",this::chooseRole);add.setTextSize(23);
        LinearLayout.LayoutParams addLp=new LinearLayout.LayoutParams(dp(40),dp(38));addLp.leftMargin=dp(8);titleRow.addView(add,addLp);
        body.addView(titleRow);gap(body,15);
        gameStrip=new HorizontalScrollView(this);gameStrip.setHorizontalScrollBarEnabled(false);
        gameTabs=new LinearLayout(this);gameStrip.addView(gameTabs);body.addView(gameStrip,new LinearLayout.LayoutParams(-1,dp(45)));
        buildGameTabs();gap(body,14);
        GoldCard stats=card(body);stats.setPadding(dp(16),dp(12),dp(16),dp(12));
        LinearLayout numbers=new LinearLayout(this);String[] labels={"封禁中角色","今日预计解封","永久封禁"};
        for(int i=0;i<3;i++){
            LinearLayout unit=column();overviewNumbers[i]=text("0",24,GOLD);unit.addView(overviewNumbers[i]);
            gap(unit,3);unit.addView(text(labels[i],11,MUTED));numbers.addView(unit,new LinearLayout.LayoutParams(0,-2,1));
        }
        stats.addView(numbers);
        EditText q=new EditText(this);q.setSingleLine();q.setTextSize(14);q.setTextColor(WHITE);q.setHintTextColor(MUTED);
        q.setHint("搜索账号 / 角色 / 备注");q.setText(search);q.setPadding(dp(16),0,dp(12),0);
        q.setBackground(surface(0xff17181d,0xff2e2a23,14));q.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        body.addView(q,new LinearLayout.LayoutParams(-1,dp(47)));gap(body,16);
        q.setOnEditorActionListener((v,id,event)->{((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(q.getWindowToken(),0);q.clearFocus();return true;});
        q.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){search=s.toString();page=30;renderRecords();}public void afterTextChanged(Editable e){}});
        recordList=column();body.addView(recordList);renderRecords();
    }
    void buildGameTabs(){
        List<String> games=new ArrayList<>();games.add("全部游戏");
        for(JSONObject g:store.query("SELECT name FROM games ORDER BY name"))games.add(g.optString("name"));
        if(!games.contains(gameFilter)){gameFilter="全部游戏";store.setting("overviewGame",gameFilter);}
        gameTabs.removeAllViews();
        for(String game:games){
            boolean chosen=game.equals(gameFilter);TextView chip=text(game,14,chosen?0xff17130c:MUTED);
            chip.setGravity(Gravity.CENTER);chip.setPadding(dp(16),0,dp(16),0);chip.setTypeface(null,chosen?1:0);
            if(chosen){GradientDrawable bg=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xffefdaa5,0xffc6a15c,0xffe2c389});bg.setCornerRadius(dp(13));chip.setBackground(bg);}
            else chip.setBackground(surface(0xff17181c,0xff343027,13));
            chip.setSelected(chosen);chip.setContentDescription(game+"分类");
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,dp(40));lp.rightMargin=dp(8);gameTabs.addView(chip,lp);
            chip.setOnClickListener(v->{gameFilter=game;store.setting("overviewGame",game);page=30;buildGameTabs();renderRecords();});
            if(chosen)chip.post(()->gameStrip.smoothScrollTo(Math.max(0,chip.getLeft()-dp(16)),0));
        }
        TextView addGame=text("＋ 游戏",14,MUTED);addGame.setGravity(Gravity.CENTER);addGame.setPadding(dp(14),0,dp(14),0);
        addGame.setBackground(surface(0xff121318,0xff343027,13));gameTabs.addView(addGame,new LinearLayout.LayoutParams(-2,dp(40)));
        addGame.setOnClickListener(v->rename("添加游戏","",name->{store.ensureGame(name);gameFilter=name;store.setting("overviewGame",name);}));
    }
    void overviewFilter(){
        LinearLayout f=form();label(f,"状态");Spinner stateChoice=spinner(f,Arrays.asList("全部状态","封禁中","即将到期","已到期","已确认解封","永久封禁"),filter,0);
        label(f,"排序");Spinner order=spinner(f,Arrays.asList("即将解封","最近封禁","最近添加"),sort,0);
        CheckBox past=new CheckBox(this);past.setText("包含历史记录");past.setTextColor(WHITE);past.setChecked(history);f.addView(past);
        ScrollView wrapper=new ScrollView(this);wrapper.addView(f);
        new AlertDialog.Builder(this).setTitle("筛选与排序").setView(wrapper).setNegativeButton("取消",null)
            .setNeutralButton("重置",(d,w)->{filter="全部状态";sort="即将解封";history=false;page=30;renderRecords();})
            .setPositiveButton("应用",(d,w)->{filter=stateChoice.getSelectedItem().toString();sort=order.getSelectedItem().toString();history=past.isChecked();page=30;renderRecords();}).show();
    }
    void updateOverviewStats(){
        int[] count=new int[3];Set<String> ids=store.currentIds();long now=System.currentTimeMillis();
        for(JSONObject b:store.bans()){
            if(!ids.contains(b.optString("id"))||(!gameFilter.equals("全部游戏")&&!gameFilter.equals(b.optString("game"))))continue;
            String st=state(b);if(st.equals(BanTime.ACTIVE))count[0]++;if(st.equals(BanTime.PERMANENT))count[2]++;
            if(b.optInt("permanent")==0&&b.optLong("confirmed")==0&&BanTime.today(b.optLong("end"),now,ZoneId.systemDefault()))count[1]++;
        }
        for(int i=0;i<3;i++)if(overviewNumbers[i]!=null)overviewNumbers[i].setText(""+count[i]);
        if(overviewOptions!=null)overviewOptions.setText(!filter.equals("全部状态")||history||!sort.equals("即将解封")?"筛选 ·":"筛选");
    }
    AdapterView.OnItemSelectedListener selected(Runnable r){return new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?>p,View v,int pos,long id){r.run();}public void onNothingSelected(AdapterView<?>p){}};}
    Spinner spinner(LinearLayout parent,List<String> items,String value,int weight){Spinner s=new Spinner(this);ArrayAdapter<String> a=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,items);s.setAdapter(a);int index=items.indexOf(value);s.setSelection(Math.max(0,index));if(weight>0)parent.addView(s,new LinearLayout.LayoutParams(0,dp(50),weight));else parent.addView(s,new LinearLayout.LayoutParams(-1,dp(50)));return s;}
    String state(JSONObject b){return BanTime.state(b.optLong("start"),b.optLong("end"),b.optInt("permanent")==1,b.optLong("confirmed"),System.currentTimeMillis());}
    void renderRecords(){
        if(recordList==null||tab!=0||detailId!=null)return;
        recordList.removeAllViews();updateOverviewStats();
        List<JSONObject> list=new ArrayList<>();Set<String> current=store.currentIds();long now=System.currentTimeMillis();String needle=search.trim().toLowerCase(Locale.ROOT);
        for(JSONObject b:store.bans()){
            if(!history&&!current.contains(b.optString("id")))continue;
            if(!gameFilter.equals("全部游戏")&&!gameFilter.equals(b.optString("game")))continue;
            String st=state(b);
            if(filter.equals("全部状态")){if(!history&&st.equals(BanTime.CONFIRMED))continue;}
            else if(filter.equals("即将到期")){if(!st.equals(BanTime.ACTIVE)||!BanTime.soon(b.optLong("end"),now))continue;}
            else if(filter.equals("已到期")){if(!st.equals(BanTime.DUE))continue;}
            else if(!filter.equals(st))continue;
            StringBuilder hay=new StringBuilder();for(String key:new String[]{"identifier","accountName","game","region","gameRoleId","nickname","note","reason"})hay.append(' ').append(b.optString(key));
            if(!hay.toString().toLowerCase(Locale.ROOT).contains(needle))continue;list.add(b);
        }
        list.sort((a,b)->{
            if(sort.equals("最近添加"))return Long.compare(b.optLong("created"),a.optLong("created"));
            if(sort.equals("最近封禁"))return Long.compare(b.optLong("start"),a.optLong("start"));
            long ae=state(a).equals(BanTime.ACTIVE)?a.optLong("end"):Long.MAX_VALUE,be=state(b).equals(BanTime.ACTIVE)?b.optLong("end"):Long.MAX_VALUE;
            int cmp=Long.compare(ae,be);return cmp!=0?cmp:Long.compare(b.optLong("start"),a.optLong("start"));
        });
        LinkedHashMap<String,List<JSONObject>> groups=new LinkedHashMap<>();
        for(JSONObject b:list)groups.computeIfAbsent(b.optString("accountId")+"|"+b.optString("game"),k->new ArrayList<>()).add(b);
        if(groups.isEmpty()){
            TextView empty=text(search.isEmpty()&&filter.equals("全部状态")?"暂无封禁账号":"没有匹配的账号",15,MUTED);
            empty.setGravity(Gravity.CENTER);empty.setPadding(0,dp(54),0,dp(54));recordList.addView(empty);return;
        }
        int shown=0;for(List<JSONObject> group:groups.values()){if(shown++>=page)break;accountBanCard(recordList,group);}
        if(groups.size()>page)recordList.addView(button("加载更多",()->{page+=30;renderRecords();}));
    }
    void accountBanCard(LinearLayout parent,List<JSONObject> rows){
        JSONObject lead=rows.get(0);for(JSONObject b:rows)if(state(b).equals(BanTime.ACTIVE)){lead=b;break;}
        final JSONObject main=lead;String st=state(main);int stateColor=st.equals(BanTime.PERMANENT)?RED:st.equals(BanTime.CONFIRMED)?GREEN:GOLD;
        GoldCard c=card(parent);c.setPadding(dp(19),dp(17),dp(19),dp(16));
        LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);
        TextView game=text(main.optString("game"),12,MUTED);top.addView(game,new LinearLayout.LayoutParams(0,-2,1));
        TextView badge=text(st.equals(BanTime.DUE)?"待确认解封":st,11,stateColor);badge.setPadding(dp(9),dp(4),dp(9),dp(4));badge.setBackground(surface(0xff24211d,0,7));top.addView(badge);c.addView(top);gap(c,12);
        String name=main.optString("accountName");TextView account=text(name.isEmpty()?main.optString("identifier"):name,25,GOLD);account.setTypeface(null,1);
        account.getPaint().setShader(new LinearGradient(0,0,0,dp(31),new int[]{0xfff4dfac,0xffbc9758,0xffedce91},null,Shader.TileMode.CLAMP));
        c.addView(account);gap(c,3);c.addView(text(main.optString("type")+(name.isEmpty()?"":" · "+main.optString("identifier")),12,MUTED));gap(c,12);
        Set<String> roleIds=new HashSet<>();int rendered=0;
        for(JSONObject b:rows){if(!roleIds.add(b.optString("roleId")))continue;if(rendered++>=2)continue;
            String nick=b.optString("nickname");String roleLine=b.optString("region")+" · "+(nick.isEmpty()?"ID "+b.optString("gameRoleId"):nick);
            TextView role=text(roleLine,13,WHITE);role.setMaxLines(1);role.setEllipsize(android.text.TextUtils.TruncateAt.END);c.addView(role);gap(c,4);
        }
        if(roleIds.size()>2)c.addView(text("另有 "+(roleIds.size()-2)+" 个角色",12,MUTED));
        gap(c,10);View divider=new View(this);divider.setBackgroundColor(0xff4a3d28);c.addView(divider,new LinearLayout.LayoutParams(-1,dp(1)));gap(c,12);
        String remaining=st.equals(BanTime.ACTIVE)?BanTime.remaining(main.optLong("end"),System.currentTimeMillis()):st.equals(BanTime.PERMANENT)?"永久封禁":st.equals(BanTime.CONFIRMED)?"已解封":"已到预计解封时间";
        c.addView(text(remaining,22,GOLD));gap(c,4);
        String footer=main.optInt("permanent")==1?"无预计解封时间":st.equals(BanTime.CONFIRMED)?"确认于 "+fmt(main.optLong("confirmed")):fmt(main.optLong("end"))+" 预计解封";
        if(roleIds.size()>1)footer+=" · "+roleIds.size()+" 个角色";
        c.addView(text(footer,11,MUTED));
        c.setContentDescription(main.optString("game")+" "+main.optString("identifier")+"封禁账号");
        c.setOnClickListener(v->{if(rows.size()==1)showDetail(main.optString("id"));else accountBanDetails(rows);});
    }
    void accountBanDetails(List<JSONObject> rows){
        String[] labels=new String[rows.size()];for(int i=0;i<rows.size();i++){JSONObject b=rows.get(i);labels[i]=b.optString("region")+" · "+b.optString("gameRoleId")+"\n"+state(b)+(b.optInt("permanent")==1?"":" · "+fmt(b.optLong("end")));}
        JSONObject first=rows.get(0);new AlertDialog.Builder(this).setTitle(first.optString("identifier")+" · "+first.optString("game"))
            .setItems(labels,(d,i)->showDetail(rows.get(i).optString("id"))).setNegativeButton("关闭",null).show();
    }
    void banCard(LinearLayout parent,JSONObject b,boolean current){GoldCard c=card(parent);String s=state(b);TextView badge=text((current?"":"历史 · ")+s,12,s.equals(BanTime.CONFIRMED)?GREEN:s.equals(BanTime.PERMANENT)?RED:GOLD);c.addView(badge);gap(c,8);c.addView(text(b.optString("game")+" · "+b.optString("region"),19,WHITE));gap(c,4);c.addView(text(b.optString("type")+" "+b.optString("identifier")+"  /  "+b.optString("nickname","")+" #"+b.optString("gameRoleId"),12,MUTED));gap(c,14);String left=s.equals(BanTime.ACTIVE)?BanTime.remaining(b.optLong("end"),System.currentTimeMillis()):s.equals(BanTime.PERMANENT)?"暂无归期":s.equals(BanTime.CONFIRMED)?"已回归":"等待确认解封";c.addView(text(left,24,GOLD));gap(c,8);c.addView(text(b.optInt("permanent")==1?"永久封禁":("预计 "+fmt(b.optLong("end"))+" 解封"),12,MUTED));c.setOnClickListener(v->showDetail(b.optString("id")));c.setContentDescription(b.optString("game")+"封禁详情");}
    void accounts(){
        heading("","账号","");body.addView(button("＋ 添加 QQ / 微信账号",()->accountForm(null)));body.addView(button("管理游戏与大区",this::manageGames));
        List<JSONObject> accounts=store.query("SELECT * FROM accounts ORDER BY type,identifier");if(accounts.isEmpty()){body.addView(text("暂无账号",15,MUTED));return;}
        for(JSONObject a:accounts){String id=a.optString("id");GoldCard c=card(body);c.addView(text(a.optString("name").isEmpty()?a.optString("identifier"):a.optString("name"),22,GOLD));c.addView(text(a.optString("type")+" · "+a.optString("identifier")+(id.equals(accountOpen)?"  ▾":"  ▸"),14,MUTED));c.setOnClickListener(v->{accountOpen=id.equals(accountOpen)?null:id;render();});c.setOnLongClickListener(v->{accountForm(a);return true;});
            if(!id.equals(accountOpen))continue;
            c.addView(button("编辑账号",()->accountForm(a)));c.addView(button("＋ 添加游戏角色",()->roleForm(id,null)));
            List<JSONObject> roles=store.query("SELECT r.*,z.name AS region,g.name AS game FROM roles r JOIN regions z ON z.id=r.regionId JOIN games g ON g.id=z.gameId WHERE r.accountId=? ORDER BY g.name,z.name,r.roleId",id);
            String prevGame="",prevRegion="";for(JSONObject r:roles){if(!prevGame.equals(r.optString("game"))){label(c,r.optString("game"));prevGame=r.optString("game");prevRegion="";}if(!prevRegion.equals(r.optString("region"))){gap(c,6);c.addView(text("  └ "+r.optString("region"),14,MUTED));prevRegion=r.optString("region");}
                TextView rt=text("      "+r.optString("roleId")+(r.optString("nickname").isEmpty()?"":" · "+r.optString("nickname")),16,WHITE);rt.setPadding(0,dp(12),0,dp(12));c.addView(rt);rt.setOnClickListener(v->showRole(r.optString("id")));rt.setOnLongClickListener(v->{roleForm(id,r);return true;});}
        }
    }
    EditText input(LinearLayout p,String hint,String value){EditText e=new EditText(this);e.setTextSize(15);e.setTextColor(WHITE);e.setHintTextColor(MUTED);e.setHint(hint);e.setText(value);e.setPadding(dp(6),dp(10),dp(6),dp(10));p.addView(e,new LinearLayout.LayoutParams(-1,-2));return e;}
    AutoCompleteTextView auto(LinearLayout p,String hint,String value,List<String> options){AutoCompleteTextView e=new AutoCompleteTextView(this);e.setHint(hint);e.setHintTextColor(MUTED);e.setTextColor(WHITE);e.setTextSize(15);e.setText(value);e.setThreshold(0);e.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_dropdown_item_1line,options));e.setOnClickListener(v->e.showDropDown());p.addView(e,new LinearLayout.LayoutParams(-1,dp(54)));return e;}
    LinearLayout form(){LinearLayout l=column();l.setPadding(dp(20),dp(4),dp(20),dp(12));return l;}
    AlertDialog dialog(String title,LinearLayout form,String positive,Runnable save){ScrollView sc=new ScrollView(this);sc.addView(form);AlertDialog d=new AlertDialog.Builder(this).setTitle(title).setView(sc).setNegativeButton("取消",null).setPositiveButton(positive,null).create();form.setTag(d);d.setOnShowListener(v->{d.getButton(-1).setOnClickListener(w->{try{save.run();d.dismiss();}catch(Exception e){toast(e.getMessage()==null?"操作失败":e.getMessage());}});d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);d.getWindow().setLayout(-1,Math.min(dp(720),(int)(getResources().getDisplayMetrics().heightPixels*.88)));});d.show();return d;}
    String value(EditText e){return e.getText().toString().trim();}
    void need(EditText e,String what){if(value(e).isEmpty())throw new IllegalArgumentException("请填写"+what);if(value(e).length()>10000)throw new IllegalArgumentException(what+"过长");}
    void changed(){Backup.prune(this,store);Reminders.schedule(this,store);render();}
    void accountForm(JSONObject a){LinearLayout f=form();Spinner type=spinner(f,Arrays.asList("QQ","微信"),a==null?"QQ":a.optString("type"),0);EditText identifier=input(f,"账号标识（必填）",a==null?"":a.optString("identifier")),name=input(f,"显示名称（选填）",a==null?"":a.optString("name"));
        if(a!=null)f.addView(button("删除账号及全部角色和封禁记录",()->confirm("删除账号？","该账号下的角色和全部封禁历史将被删除，无法撤销。",()->{((AlertDialog)f.getTag()).dismiss();Reminders.cancelAll(this,store);store.getWritableDatabase().delete("accounts","id=?",new String[]{a.optString("id")});accountOpen=null;changed();toast("账号已删除");})));
        dialog(a==null?"添加账号":"编辑账号",f,"保存",()->{need(identifier,"账号标识");String t=type.getSelectedItem().toString();JSONObject exists=store.one("SELECT * FROM accounts WHERE type=? AND identifier=?",t,value(identifier));if(exists!=null&&(a==null||!exists.optString("id").equals(a.optString("id"))))throw new IllegalArgumentException("该账号已存在");if(a==null)accountOpen=store.ensureAccount(t,value(identifier),value(name));else store.getWritableDatabase().execSQL("UPDATE accounts SET type=?,identifier=?,name=? WHERE id=?",new Object[]{t,value(identifier),value(name),a.optString("id")});changed();});
    }
    void roleForm(String account,JSONObject existing){
        LinearLayout f=form();List<String> games=new ArrayList<>();for(JSONObject g:store.query("SELECT name FROM games ORDER BY name"))games.add(g.optString("name"));AutoCompleteTextView game=auto(f,"游戏名称：选择或自行输入（必填）",existing==null?"":existing.optString("game"),games);AutoCompleteTextView region=auto(f,"游戏大区：选择或自行输入（必填）",existing==null?"":existing.optString("region"),new ArrayList<>());
        game.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int x){}public void afterTextChanged(Editable e){List<String> regions=new ArrayList<>();for(JSONObject z:store.query("SELECT z.name FROM regions z JOIN games g ON g.id=z.gameId WHERE g.name=?",value(game)))regions.add(z.optString("name"));region.setAdapter(new ArrayAdapter<>(MainActivity.this,android.R.layout.simple_dropdown_item_1line,regions));}public void onTextChanged(CharSequence s,int a,int b,int c){}});
        EditText role=input(f,"角色 ID（必填）",existing==null?"":existing.optString("roleId")),nick=input(f,"游戏昵称（选填）",existing==null?"":existing.optString("nickname"));
        if(existing!=null)f.addView(button("删除角色及全部封禁历史",()->confirm("删除角色？","该角色的全部封禁记录将被删除。",()->{((AlertDialog)f.getTag()).dismiss();Reminders.cancelAll(this,store);store.getWritableDatabase().delete("roles","id=?",new String[]{existing.optString("id")});detailId=null;changed();})));
        dialog(existing==null?"添加游戏角色":"编辑角色",f,"保存",()->{need(game,"游戏名称");need(region,"游戏大区");need(role,"角色 ID");SQLiteDatabase db=store.getWritableDatabase();db.beginTransaction();try{String gid=store.ensureGame(value(game)),zid=store.ensureRegion(gid,value(region));JSONObject duplicate=store.one("SELECT id FROM roles WHERE accountId=? AND regionId=? AND roleId=?",account,zid,value(role));if(duplicate!=null&&(existing==null||!duplicate.optString("id").equals(existing.optString("id"))))throw new IllegalArgumentException("该账号、大区下的角色 ID 已存在");if(existing==null)store.ensureRole(account,zid,value(role),value(nick));else db.execSQL("UPDATE roles SET regionId=?,roleId=?,nickname=? WHERE id=?",new Object[]{zid,value(role),value(nick),existing.optString("id")});db.setTransactionSuccessful();}finally{db.endTransaction();}changed();});
    }
    void chooseRole(){List<JSONObject> roles=store.query("SELECT r.*,a.type,a.identifier,g.name AS game,z.name AS region FROM roles r JOIN accounts a ON a.id=r.accountId JOIN regions z ON z.id=r.regionId JOIN games g ON g.id=z.gameId ORDER BY a.identifier,g.name,z.name");if(roles.isEmpty()){confirm("先添加游戏角色","请在账号页添加 QQ / 微信账号，再添加游戏大区和角色。",()->{tab=1;render();});return;}String[] items=new String[roles.size()];for(int i=0;i<items.length;i++){JSONObject r=roles.get(i);items[i]=r.optString("identifier")+" · "+r.optString("game")+" · "+r.optString("region")+" · "+r.optString("roleId");}new AlertDialog.Builder(this).setTitle("选择所属角色").setItems(items,(d,i)->new BanForm(roles.get(i).optString("id"),null).show()).setNegativeButton("取消",null).show();}
    void showRole(String id){roleHistory=id;detailId=null;body.removeAllViews();body.addView(button("‹ 返回账号",()->{roleHistory=null;render();}));JSONObject r=store.one("SELECT r.*,g.name AS game,z.name AS region FROM roles r JOIN regions z ON z.id=r.regionId JOIN games g ON g.id=z.gameId WHERE r.id=?",id);if(r==null){render();return;}heading("角色档案",r.optString("nickname").isEmpty()?r.optString("roleId"):r.optString("nickname"),r.optString("game")+" · "+r.optString("region")+" · ID "+r.optString("roleId"));body.addView(button("＋ 新增一次封禁",()->new BanForm(id,null).show()));body.addView(button("编辑角色",()->roleForm(r.optString("accountId"),r)));Set<String> ids=store.currentIds();boolean any=false;for(JSONObject b:store.bans())if(id.equals(b.optString("roleId"))){banCard(body,b,ids.contains(b.optString("id")));any=true;}if(!any)body.addView(text("这个角色还没有封禁记录。",14,MUTED));}
    void showDetail(String id){detailId=id;render();}
    void showDetailContent(String id){JSONObject b=store.ban(id);if(b==null){detailId=null;render();return;}body.addView(button("‹ 返回",()->{detailId=null;if(roleHistory!=null)showRole(roleHistory);else render();}));heading("封禁档案",b.optString("game"),b.optString("region")+" · "+b.optString("gameRoleId"));banCard(body,b,store.currentIds().contains(id));GoldCard info=card(body);
        String[] keys={"账号","昵称","封禁开始","预计解封","封禁时长","实际确认解封","提醒","原因","备注"};String duration=b.optInt("permanent")==1?"永久":duration(b.optLong("end")-b.optLong("start"));String[] vals={b.optString("type")+" · "+b.optString("identifier"),b.optString("nickname"),fmt(b.optLong("start")),b.optInt("permanent")==1?"永久":fmt(b.optLong("end")),duration,b.optLong("confirmed")>0?fmt(b.optLong("confirmed")):"尚未确认",REMIND[b.optInt("remind")],b.optString("reason"),b.optString("note")};for(int i=0;i<keys.length;i++){label(info,keys[i]);TextView t=text(vals[i].isEmpty()?"未填写":vals[i],15,WHITE);t.setTextIsSelectable(true);info.addView(t);}
        for(JSONObject a:store.query("SELECT * FROM attachments WHERE banId=?",id))thumbnail(body,new File(Backup.directory(this),a.optString("file")));
        if(b.optLong("confirmed")==0)body.addView(button("确认实际已解封",()->confirm("确认实际已解封？","请先在游戏中核实。确认后将取消本次封禁的后续提醒。",()->{store.getWritableDatabase().execSQL("UPDATE bans SET confirmed=? WHERE id=?",new Object[]{System.currentTimeMillis(),id});changed();})));
        else body.addView(button("撤销解封确认",()->confirm("撤销解封确认？","将根据封禁时间重新显示状态并恢复提醒。",()->{store.getWritableDatabase().execSQL("UPDATE bans SET confirmed=0 WHERE id=?",new Object[]{id});changed();})));
        body.addView(button("编辑封禁记录",()->new BanForm(b.optString("roleId"),b).show()));body.addView(button("记录该角色新的封禁",()->new BanForm(b.optString("roleId"),null).show()));body.addView(button("删除本次封禁记录",()->confirm("删除封禁记录？","附件和本次记录会一并删除，无法撤销。",()->{Reminders.cancel(this,id);store.getWritableDatabase().delete("bans","id=?",new String[]{id});detailId=null;changed();})));
    }
    String fmt(long t){return Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).format(FMT);}
    String duration(long ms){long m=ms/60000;return m/1440+"天 "+m%1440/60+"小时 "+m%60+"分钟";}
    void thumbnail(LinearLayout p,File file){if(!file.isFile())return;BitmapFactory.Options opt=new BitmapFactory.Options();opt.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getAbsolutePath(),opt);int sample=1;while(opt.outWidth/sample>720||opt.outHeight/sample>960)sample*=2;opt.inJustDecodeBounds=false;opt.inSampleSize=sample;Bitmap image=BitmapFactory.decodeFile(file.getAbsolutePath(),opt);if(image==null){p.addView(text("无法预览此图片",13,MUTED));return;}ImageView v=new ImageView(this);v.setImageBitmap(image);v.setAdjustViewBounds(true);v.setScaleType(ImageView.ScaleType.FIT_CENTER);v.setContentDescription("封禁截图");p.addView(v,new LinearLayout.LayoutParams(-1,dp(220)));gap(p,10);}
    void pickDate(EditText field,long initial,java.util.function.LongConsumer change){ZonedDateTime z=Instant.ofEpochMilli(initial).atZone(ZoneId.systemDefault());new DatePickerDialog(this,(d,y,m,day)->new TimePickerDialog(this,(t,h,min)->{long millis=LocalDateTime.of(y,m+1,day,h,min).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();field.setText(fmt(millis));change.accept(millis);},z.getHour(),z.getMinute(),true).show(),z.getYear(),z.getMonthValue()-1,z.getDayOfMonth()).show();}
    EditText dateField(LinearLayout f,String title,long initial,java.util.function.LongConsumer change){label(f,title);EditText e=input(f,"",fmt(initial));e.setFocusable(false);e.setOnClickListener(v->pickDate(e,initialFrom(e,initial),change));return e;}
    long initialFrom(EditText e,long fallback){try{return LocalDateTime.parse(value(e),FMT).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();}catch(Exception x){return fallback;}}
    class BanForm {
        String role;JSONObject old;LinearLayout f,attachmentBox;EditText startField,endField,days,hours,minutes,reason,note;CheckBox permanent;Spinner mode,remind;TextView calculation;long start,end;AlertDialog dlg;boolean saved=false,copying=false,updating=false;List<File> added=new ArrayList<>();List<JSONObject> existing=new ArrayList<>();Set<String> removed=new HashSet<>();
        BanForm(String r,JSONObject b){role=r;old=b;start=b==null?(System.currentTimeMillis()/60000*60000):b.optLong("start");end=b==null?start+86400000:b.optLong("end");}
        void show(){f=form();f.addView(text("封禁时间与预计解封",18,GOLD));JSONObject r=store.one("SELECT r.roleId,r.nickname,z.name AS region,g.name AS game FROM roles r JOIN regions z ON z.id=r.regionId JOIN games g ON g.id=z.gameId WHERE r.id=?",role);f.addView(text(r.optString("game")+" · "+r.optString("region")+" · "+r.optString("roleId"),13,MUTED));startField=dateField(f,"封禁开始时间",start,v->{start=v;calculate();});
            permanent=new CheckBox(MainActivity.this);permanent.setText("永久封禁");permanent.setTextColor(WHITE);permanent.setChecked(old!=null&&old.optInt("permanent")==1);f.addView(permanent);
            mode=spinner(f,Arrays.asList("填写封禁时长","填写预计解封时间"),"填写封禁时长",0);LinearLayout timeRow=new LinearLayout(MainActivity.this);f.addView(timeRow);days=number(timeRow,"天",Math.max(0,(end-start)/86400000));hours=number(timeRow,"小时",Math.max(0,(end-start)/3600000%24));minutes=number(timeRow,"分钟",Math.max(0,(end-start)/60000%60));endField=dateField(f,"预计解封时间",Math.max(end,start+60000),v->{end=v;calculate();});
            calculation=text("",14,GOLD);f.addView(calculation);label(f,"到期提醒");remind=spinner(f,Arrays.asList(REMIND),REMIND[old==null?store.defaultReminder():old.optInt("remind")],0);
            reason=input(f,"封禁原因（选填）",old==null?"":old.optString("reason"));note=input(f,"备注（选填）",old==null?"":old.optString("note"));note.setMinLines(2);label(f,"附件截图");attachmentBox=column();f.addView(attachmentBox);if(old!=null)existing=store.query("SELECT * FROM attachments WHERE banId=?",old.optString("id"));renderAttachments();
            f.addView(button("＋ 添加附件截图",()->{if(copying)return;pendingForm=this;Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);startActivityForResult(i,20);}));
            TextWatcher watcher=new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int n){}public void afterTextChanged(Editable e){calculate();}public void onTextChanged(CharSequence s,int a,int b,int c){}};days.addTextChangedListener(watcher);hours.addTextChangedListener(watcher);minutes.addTextChangedListener(watcher);mode.setOnItemSelectedListener(selected(this::calculate));permanent.setOnCheckedChangeListener((b,v)->calculate());
            dlg=dialog(old==null?"新增封禁记录":"编辑封禁记录",f,"保存",this::save);dlg.setOnDismissListener(d->{if(pendingForm==this)pendingForm=null;if(!saved)for(File file:added)file.delete();});calculate();
        }
        EditText number(LinearLayout row,String hint,long val){LinearLayout c=column();row.addView(c,new LinearLayout.LayoutParams(0,-2,1));EditText e=input(c,hint,""+val);e.setInputType(2);e.setSingleLine();c.addView(text(hint,12,MUTED));return e;}
        long num(EditText e){return value(e).isEmpty()?0:Long.parseLong(value(e));}
        void calculate(){if(calculation==null||updating)return;boolean p=permanent.isChecked(),duration=mode.getSelectedItemPosition()==0;for(EditText e:new EditText[]{days,hours,minutes})e.setEnabled(!p&&duration);endField.setEnabled(!p&&!duration);remind.setEnabled(!p);mode.setEnabled(!p);if(p){calculation.setText("永久封禁 · 不安排到期提醒");return;}try{if(duration){end=BanTime.end(start,num(days),num(hours),num(minutes));endField.setText(fmt(end));}else if(end>start){updating=true;long mins=(end-start)/60000;days.setText(""+(mins/1440));hours.setText(""+(mins%1440/60));minutes.setText(""+(mins%60));updating=false;}calculation.setText(end>start?("封禁时长 "+MainActivity.this.duration(end-start)+"\n预计解封 "+fmt(end)):"预计解封时间必须晚于封禁开始时间");}catch(Exception ex){calculation.setText("请填写有效封禁时长");}}
        void renderAttachments(){attachmentBox.removeAllViews();for(JSONObject a:existing)if(!removed.contains(a.optString("id"))){thumbnail(attachmentBox,new File(Backup.directory(MainActivity.this),a.optString("file")));attachmentBox.addView(button("移除此附件",()->{removed.add(a.optString("id"));renderAttachments();}));}for(File file:added){thumbnail(attachmentBox,file);attachmentBox.addView(button("移除此附件",()->{added.remove(file);file.delete();renderAttachments();}));}}
        void save(){if(copying)throw new IllegalArgumentException("正在添加图片，请稍候");boolean p=permanent.isChecked();if(start>System.currentTimeMillis())throw new IllegalArgumentException("封禁开始时间不能晚于当前时间");if(!p){if(mode.getSelectedItemPosition()==0)end=BanTime.end(start,num(days),num(hours),num(minutes));if(end<=start)throw new IllegalArgumentException("解封时间必须晚于封禁开始时间");}long finalEnd=p?0:end;String id=old==null?Store.uuid():old.optString("id");
            JSONObject duplicate=store.one("SELECT id FROM bans WHERE roleId=? AND start=? AND end=? AND permanent=? AND id<>?",role,""+start,""+finalEnd,p?"1":"0",id);if(duplicate!=null)throw new IllegalArgumentException("同一角色已存在相同时间的封禁，请检查重复记录");
            if(old!=null&&old.optLong("confirmed")>0&&start>old.optLong("confirmed"))throw new IllegalArgumentException("封禁开始时间不能晚于解封确认时间");
            SQLiteDatabase db=store.getWritableDatabase();db.beginTransaction();List<File> moved=new ArrayList<>();try{db.execSQL("INSERT OR REPLACE INTO bans(id,roleId,start,end,permanent,confirmed,reason,note,remind,created) VALUES(?,?,?,?,?,?,?,?,?,?)",new Object[]{id,role,start,finalEnd,p?1:0,old==null?0:old.optLong("confirmed"),value(reason),value(note),p?0:remind.getSelectedItemPosition(),old==null?System.currentTimeMillis():old.optLong("created")});
                // REPLACE would cascade attachments, so reinsert retained attachment rows explicitly.
                for(JSONObject a:existing)if(!removed.contains(a.optString("id")))db.execSQL("INSERT OR IGNORE INTO attachments VALUES(?,?,?,?)",new Object[]{a.optString("id"),id,a.optString("file"),a.optString("mime")});
                for(File file:added){String name=Store.uuid()+".img";File dest=new File(Backup.directory(MainActivity.this),name);Files.move(file.toPath(),dest.toPath());moved.add(dest);db.execSQL("INSERT INTO attachments VALUES(?,?,?,?)",new Object[]{Store.uuid(),id,name,"image/*"});}db.setTransactionSuccessful();
            }catch(Exception ex){for(int i=0;i<moved.size();i++)try{Files.move(moved.get(i).toPath(),added.get(i).toPath());}catch(Exception ignored){}throw new IllegalArgumentException("保存失败："+ex.getMessage());}finally{db.endTransaction();}saved=true;for(File file:added)file.delete();detailId=id;changed();if(!p&&remind.getSelectedItemPosition()>0&&Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)handler.post(()->requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},40));
        }
    }
    void manageGames(){List<JSONObject> games=store.query("SELECT * FROM games ORDER BY name");String[] names=new String[games.size()+1];names[0]="＋ 添加游戏";for(int i=0;i<games.size();i++)names[i+1]=games.get(i).optString("name");new AlertDialog.Builder(this).setTitle("游戏与大区").setItems(names,(d,i)->{if(i==0)rename("新增游戏","",v->store.ensureGame(v));else manageGame(games.get(i-1));}).setNegativeButton("关闭",null).show();}
    interface NameSave{void save(String value);}
    void rename(String title,String initial,NameSave save){LinearLayout f=form();EditText e=input(f,"名称（必填）",initial);dialog(title,f,"保存",()->{need(e,"名称");save.save(value(e));changed();});}
    void manageGame(JSONObject game){String gid=game.optString("id");List<JSONObject> regions=store.query("SELECT * FROM regions WHERE gameId=? ORDER BY name",gid);String[] items=new String[regions.size()+3];items[0]="重命名游戏";items[1]="＋ 添加大区";items[2]="删除游戏及关联角色";for(int i=0;i<regions.size();i++)items[i+3]=regions.get(i).optString("name");new AlertDialog.Builder(this).setTitle(game.optString("name")).setItems(items,(d,i)->{if(i==0)rename("重命名游戏",game.optString("name"),v->store.getWritableDatabase().execSQL("UPDATE games SET name=? WHERE id=?",new Object[]{v,gid}));else if(i==1)rename("添加大区","",v->store.ensureRegion(gid,v));else if(i==2)confirm("删除游戏？","所有账号下该游戏的角色和封禁历史都将删除。",()->{Reminders.cancelAll(this,store);store.getWritableDatabase().delete("games","id=?",new String[]{gid});changed();});else{JSONObject z=regions.get(i-3);new AlertDialog.Builder(this).setTitle(z.optString("name")).setItems(new String[]{"重命名大区","删除大区及关联角色"},(x,w)->{if(w==0)rename("重命名大区",z.optString("name"),v->store.getWritableDatabase().execSQL("UPDATE regions SET name=? WHERE id=?",new Object[]{v,z.optString("id")}));else confirm("删除大区？","所有账号下该大区的角色及封禁历史都将删除。",()->{Reminders.cancelAll(this,store);store.getWritableDatabase().delete("regions","id=?",new String[]{z.optString("id")});changed();});}).show();}}).setNegativeButton("关闭",null).show();}
    void imports(){
        heading("","导入","");
        GoldCard web=card(body);
        web.addView(text("网页导入",20,GOLD));
        gap(web,6);web.addView(text("识别待完善",12,MUTED));
        web.addView(button("打开浏览器",()->startActivity(new Intent(this,BrowserActivity.class))));
        GoldCard image=card(body);
        image.addView(text("图片导入",20,GOLD));
        gap(image,6);image.addView(text("识别待完善",12,MUTED));
        image.addView(button("选择图片",()->{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,21);}));
        imagePreview=column();image.addView(imagePreview);
        GoldCard backup=card(body);backup.addView(text("备份恢复",20,GOLD));
        backup.addView(button("选择备份",this::restoreChoice));
    }
    void settings(){
        heading("","设置","");
        GoldCard reminder=card(body);reminder.addView(text("默认提醒",20,GOLD));
        Spinner rule=spinner(reminder,Arrays.asList(REMIND),REMIND[store.defaultReminder()],0);
        rule.setOnItemSelectedListener(selected(()->store.setting("defaultRemind",""+rule.getSelectedItemPosition())));
        boolean allowed=Build.VERSION.SDK_INT<33||checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED;
        reminder.addView(button(allowed?"通知已开启 · 管理":"开启通知",this::notificationPermission));
        GoldCard backup=card(body);backup.addView(text("备份与恢复",20,GOLD));
        backup.addView(button("导出备份",()->{Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/zip").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"沉淀一下_"+LocalDate.now()+".zip");startActivityForResult(i,30);}));
        backup.addView(button("恢复备份",this::restoreChoice));
        body.addView(text("沉淀一下 0.1.2",12,MUTED));
    }
    void notificationPermission(){if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},40);}else startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,getPackageName()));}
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){super.onRequestPermissionsResult(request,permissions,results);if(request==40){changed();if(results.length==0||results[0]!=PackageManager.PERMISSION_GRANTED)toast("未获得通知权限，可在系统设置中开启");}}
    void restoreChoice(){if(busy)return;new AlertDialog.Builder(this).setTitle("选择恢复方式").setItems(new String[]{"合并：保留本地记录，补充备份数据","替换：清空本地档案后恢复备份"},(d,i)->{replaceRestore=i==1;if(replaceRestore)confirm("替换全部本地数据？","当前账号、角色、封禁记录、附件和设置将被备份内容替换。建议先导出当前备份。选中文件并校验通过后执行。",this::pickBackup);else pickBackup();}).setNegativeButton("取消",null).show();}
    void pickBackup(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,31);}
    interface Work{String run()throws Exception;}
    void work(String title,Work task,Runnable done){if(busy)return;busy=true;ProgressDialog p=new ProgressDialog(this);p.setMessage(title);p.setCancelable(false);p.show();io.execute(()->{String result;boolean ok;try{result=task.run();ok=true;}catch(Exception e){result="操作失败："+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage());ok=false;}String message=result;boolean success=ok;runOnUiThread(()->{busy=false;if(isFinishing()||isDestroyed()){store.close();return;}p.dismiss();if(!message.isEmpty())toast(message);if(success&&done!=null)done.run();});});}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null)return;Uri uri=data.getData();if(request==30&&uri!=null){work("正在导出完整备份…",()->{try(OutputStream out=getContentResolver().openOutputStream(uri,"wt")){if(out==null)throw new IOException("无法打开目标文件");Backup.write(this,store,out);}return "备份已导出";},null);}else if(request==31&&uri!=null){boolean replace=replaceRestore;work("正在校验并恢复备份…",()->{Reminders.cancelAll(this,store);try(InputStream in=getContentResolver().openInputStream(uri)){if(in==null)throw new IOException("无法打开备份");int count=Backup.restore(this,store,in,replace);return "恢复完成，新增 "+count+" 条封禁记录";}finally{Reminders.schedule(this,store);}},()->{detailId=null;accountOpen=null;render();});}else if(request==20&&pendingForm!=null){BanForm form=pendingForm;List<Uri> uris=new ArrayList<>();if(data.getClipData()!=null){for(int i=0;i<data.getClipData().getItemCount();i++)uris.add(data.getClipData().getItemAt(i).getUri());}else if(uri!=null)uris.add(uri);form.copying=true;work("正在保存图片…",()->{List<File> staged=new ArrayList<>();try{if(uris.size()+form.added.size()+form.existing.size()-form.removed.size()>20)throw new IOException("每条记录最多 20 张图片");for(Uri u:uris)staged.add(copyImage(u));form.added.addAll(staged);return "已添加 "+staged.size()+" 张附件";}catch(Exception e){for(File file:staged)file.delete();throw e;}finally{form.copying=false;}},form::renderAttachments);}else if(request==21&&uri!=null){work("正在加载图片…",()->{File file=copyImage(uri);runOnUiThread(()->{if(imagePreview!=null){imagePreview.removeAllViews();thumbnail(imagePreview,file);imagePreview.addView(text("识别待完善",14,GOLD));}file.delete();});return "";},null);}}
    File copyImage(Uri uri)throws Exception{String mime=getContentResolver().getType(uri);if(mime==null||!mime.startsWith("image/"))throw new IOException("请选择图片文件");File f=new File(getCacheDir(),Store.uuid()+".img");try{try(InputStream in=getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(f)){if(in==null)throw new IOException("无法打开图片");byte[] buf=new byte[16384];int n;long size=0;while((n=in.read(buf))!=-1){size+=n;if(size>32*1024*1024)throw new IOException("单张图片不能超过 32 MB");out.write(buf,0,n);}}BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeFile(f.getAbsolutePath(),options);if(options.outWidth<=0||options.outHeight<=0)throw new IOException("不支持此图片格式");return f;}catch(Exception e){f.delete();throw e;}}
    @Override public void onBackPressed(){if(busy)return;if(detailId!=null){detailId=null;if(roleHistory!=null)showRole(roleHistory);else render();}else if(roleHistory!=null){roleHistory=null;render();}else if(tab!=0){tab=0;render();}else super.onBackPressed();}
}
