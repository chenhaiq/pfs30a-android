package local.pfs30a;

import android.content.Context;
import android.graphics.*;
import android.view.View;
import java.util.*;

/** LDL history using actual test dates; user-selected targets in mmol/L. */
public class LdlChart extends View {
    final List<Float> values;
    final List<Long> dates;
    final List<String> labels;
    final Paint paint = new Paint(3);
    final float density;
    final boolean uniform;
    public LdlChart(Context context, List<Float> values, List<Long> dates, List<String> labels, boolean uniform) {
        super(context);
        this.uniform=uniform;
        this.values=values; this.dates=dates; this.labels=labels;
        density=getResources().getDisplayMetrics().density;
        setContentDescription("LDL 趋势，"+values.size()+" 次检测。自定目标，2.2 合格，1.8 优秀，单位 mmol/L。");
    }
    float d(float n) { return n*density; }
    void text(Canvas c,String text,float x,float y,int color,int size) {
        paint.setColor(color); paint.setTextSize(d(size)); paint.setStyle(Paint.Style.FILL);
        c.drawText(text,x,y,paint);
    }
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        float left=d(38),right=getWidth()-d(18),top=d(22),bottom=getHeight()-d(40);
        float max=2.8f;
        for(float v:values) if(Float.isFinite(v)) max=Math.max(max,v+0.4f);
        max=(float)Math.ceil(max*2)/2;
        paint.setStrokeWidth(d(1));
        for(int i=0;i<=4;i++) {
            float v=max*i/4, y=bottom-(bottom-top)*v/max;
            paint.setColor(0xffdce5e2); c.drawLine(left,y,right,y,paint);
            text(c,String.format(Locale.US,"%.1f",v),0,y+d(4),0xff687c78,11);
        }
        float[] targets={2.2f,1.8f};
        String[] names={"合格 ≤2.2","优秀 ≤1.8"};
        int[] colors={0xffa57927,0xff21856d};
        for(int i=0;i<2;i++) {
            float y=bottom-(bottom-top)*targets[i]/max;
            paint.setColor(colors[i]); paint.setPathEffect(new DashPathEffect(new float[]{d(5),d(4)},0));
            c.drawLine(left,y,right,y,paint); paint.setPathEffect(null);
            text(c,names[i],left+d(5),y-d(5),colors[i],12);
        }
        long first=dates.get(0),last=dates.get(dates.size()-1);
        float oldX=0,oldY=0; boolean previous=false;
        for(int i=0;i<values.size();i++) {
            float v=values.get(i);
            if(!Float.isFinite(v)) { previous=false; continue; }
            float fraction;
            if(uniform) fraction=values.size()==1?0.5f:i/(float)(values.size()-1);
            else fraction=first==last?0.5f:(dates.get(i)-first)/(float)(last-first);
            float x=left+(right-left)*fraction;
            float y=bottom-(bottom-top)*v/max;
            paint.setColor(0xff173b38); paint.setStrokeWidth(d(2));
            if(previous) c.drawLine(oldX,oldY,x,y,paint);
            c.drawCircle(x,y,d(3),paint);
            oldX=x; oldY=y; previous=true;
        }
        text(c,labels.get(0),left,bottom+d(24),0xff687c78,11);
        String end=labels.get(labels.size()-1);
        if(values.size()>1) {
            paint.setTextSize(d(11));
            text(c,end,right-paint.measureText(end),bottom+d(24),0xff687c78,11);
        }
    }
}
