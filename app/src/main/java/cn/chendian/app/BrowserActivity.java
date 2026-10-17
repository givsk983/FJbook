package cn.chendian.app;
import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.Color;
import android.net.Uri;
import android.webkit.*;
import android.widget.*;

public class BrowserActivity extends Activity {
    WebView web;EditText url;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setPadding(16,20,16,12);root.setBackgroundColor(0xff0b0c10);
        root.setOnApplyWindowInsetsListener((v,i)->{v.setPadding(16,i.getSystemWindowInsetTop()+12,16,i.getSystemWindowInsetBottom()+12);return i;});
        TextView title=new TextView(this);title.setText("浏览器");title.setTextColor(0xffd4b16a);title.setTextSize(20);root.addView(title);
        LinearLayout bar=new LinearLayout(this);url=new EditText(this);url.setSingleLine();url.setTextColor(Color.WHITE);url.setTextSize(14);url.setHint("输入 https:// 网址");url.setHintTextColor(Color.GRAY);url.setInputType(17);bar.addView(url,new LinearLayout.LayoutParams(0,-2,1));
        Button go=new Button(this);go.setText("前往");go.setOnClickListener(v->open(url.getText().toString()));bar.addView(go);root.addView(bar);
        web=new WebView(this);web.getSettings().setJavaScriptEnabled(true);web.getSettings().setAllowFileAccess(false);web.getSettings().setAllowContentAccess(false);web.getSettings().setDomStorageEnabled(true);web.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web,false);web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){return !"https".equalsIgnoreCase(r.getUrl().getScheme());}
            @Override public void onPageFinished(WebView v,String u){url.setText(u);}
        });root.addView(web,new LinearLayout.LayoutParams(-1,0,1));
        Button imp=new Button(this);imp.setText("识别待完善");imp.setEnabled(false);root.addView(imp);
        Button back=new Button(this);back.setText("返回导入页");back.setOnClickListener(v->finish());root.addView(back);setContentView(root);
    }
    void open(String raw){String s=raw.trim();if(!s.contains("://"))s="https://"+s;Uri u=Uri.parse(s);if(!"https".equalsIgnoreCase(u.getScheme())||u.getHost()==null){Toast.makeText(this,"请输入有效 HTTPS 网址",0).show();return;}web.loadUrl(s);}
    @Override public void onBackPressed(){if(web.canGoBack())web.goBack();else super.onBackPressed();}
    @Override public void onDestroy(){web.destroy();super.onDestroy();}
}
