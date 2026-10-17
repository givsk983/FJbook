package cn.chendian.app;
import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.widget.LinearLayout;

public class GoldCard extends LinearLayout {
    public GoldCard(Context c) { super(c);setOrientation(VERTICAL);int p=(int)(18*c.getResources().getDisplayMetrics().density);setPadding(p,p,p,p);setBackground(new Foil(c)); }
    static class Foil extends Drawable {
        final Paint paint=new Paint(3);final float density;
        Foil(Context c){density=c.getResources().getDisplayMetrics().density;}
        public void draw(Canvas canvas) {
            Rect b=getBounds();RectF r=new RectF(b.left+1,b.top+1,b.right-1,b.bottom-1);float radius=18*density;
            paint.setStyle(Paint.Style.FILL);paint.setShader(new LinearGradient(0,0,b.width(),b.height(),new int[]{0xff24232a,0xff15161c,0xff1c1b20},null,Shader.TileMode.CLAMP));canvas.drawRoundRect(r,radius,radius,paint);
            canvas.save();Path clip=new Path();clip.addRoundRect(r,radius,radius,Path.Direction.CW);canvas.clipPath(clip);
            paint.setShader(null);paint.setColor(0x08ddbe7d);paint.setStrokeWidth(density/2);
            for(float x=-b.height();x<b.width();x+=9*density)canvas.drawLine(x,0,x+b.height(),b.height(),paint);
            paint.setShader(new RadialGradient(b.width()*.1f,0,Math.max(1,b.width()*.8f),new int[]{0x18e0bd71,0x00e0bd71},null,Shader.TileMode.CLAMP));canvas.drawRect(r,paint);canvas.restore();
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(density);paint.setShader(new LinearGradient(0,0,b.width(),b.height(),new int[]{0xffa98a50,0xff403a2c,0xff78603b},null,Shader.TileMode.CLAMP));canvas.drawRoundRect(r,radius,radius,paint);paint.setShader(null);
        }
        public void setAlpha(int a){}public void setColorFilter(ColorFilter f){}public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }
}
