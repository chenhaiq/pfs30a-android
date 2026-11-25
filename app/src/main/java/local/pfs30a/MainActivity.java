package local.pfs30a;

import android.app.*;
import android.os.*;
import android.content.*;
import android.hardware.usb.*;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.view.*;
import android.widget.*;
import com.hoho.android.usbserial.driver.*;
import java.io.*;
import java.nio.charset.Charset;
import java.util.*;
import java.util.regex.*;

public class MainActivity extends Activity {
    TextView guide;
    LinearLayout layout, records;
    SQLiteDatabase db;
    volatile UsbSerialPort port;
    int chartMode = 0;
    TextView connection;
    Button connectButton;
    final int ink = 0xff173b38;
    final String permission = "local.pfs30a.USB";
    final String[] fields = {"TC","TG","HDL","LDL","TC/HDL"};
    final BroadcastReceiver receiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            if (permission.equals(intent.getAction())) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && device != null) open(device);
                else guide.setText("USB 授权未通过，请点击连接重试。");
            }
        }
    };

    public void onCreate(Bundle state) {
        super.onCreate(state);
        db = openOrCreateDatabase("records.db", MODE_PRIVATE, null);
        db.execSQL("CREATE TABLE IF NOT EXISTS records (report TEXT PRIMARY KEY, date TEXT, summary TEXT, csv TEXT)");
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xfff3f6f5);
        layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(20),dp(40),dp(20),dp(32));
        scroll.addView(layout);
        setContentView(scroll);
        layout.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(dp(20), insets.getSystemWindowInsetTop()+dp(20), dp(20), insets.getSystemWindowInsetBottom()+dp(24));
            return insets;
        });
        layout.addView(label("血脂记录",30,true));
        connection = label("● 设备未连接",14,false);
        connection.setPadding(0,dp(16),0,dp(8));
        layout.addView(connection);
        connectButton = new Button(this);
        connectButton.setText("连接血脂仪");
        connectButton.setTextColor(0xffffffff);
        connectButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(ink));
        connectButton.setOnClickListener(v -> connect());
        layout.addView(connectButton);
        guide = label("用 USB OTG 连接仪器。连接后在历史结果详情页长按 M，记录会自动保存。",14,false);
        guide.setPadding(0,dp(12),0,dp(24));
        layout.addView(guide);
        records = new LinearLayout(this);
        records.setOrientation(LinearLayout.VERTICAL);
        layout.addView(records);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver,new IntentFilter(permission),Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(receiver,new IntentFilter(permission));
        refresh();
    }
    int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    TextView label(String text, int size, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(ink);
        if(bold) view.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        return view;
    }
    void connected(boolean ready) {
        connection.setText(ready ? "● 已连接 · PFS-30A" : "● 设备未连接");
        connection.setTextColor(ready ? 0xff16765b : 0xff687c78);
        connectButton.setVisibility(ready ? View.GONE : View.VISIBLE);
    }
    void next() {
        guide.setText("在仪器历史列表选记录，短按电源键进入结果详情，长按 M 打印。收到后自动保存，可继续选择下一条。");
    }
    void connect() {
        if (port != null) { next(); return; }
        UsbManager manager = (UsbManager)getSystemService(USB_SERVICE);
        for (UsbDevice device : manager.getDeviceList().values()) {
            if (device.getVendorId()==0x1a86 && device.getProductId()==0x7523) {
                if (manager.hasPermission(device)) open(device);
                else manager.requestPermission(device, PendingIntent.getBroadcast(this,0,
                    new Intent(permission).setPackage(getPackageName()),PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
                return;
            }
        }
        guide.setText("未发现血脂仪。请用支持数据传输的 OTG 线直接连接手机与仪器，再点击连接。");
    }
    void open(UsbDevice device) {
        try {
            UsbManager manager = (UsbManager)getSystemService(USB_SERVICE);
            UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(device);
            if (driver == null) throw new IOException("无法识别 USB 串口");
            UsbSerialPort serial = driver.getPorts().get(0);
            serial.open(manager.openDevice(device));
            try { serial.setParameters(115200,8,1,0); }
            catch (Exception error) { serial.close(); throw error; }
            port = serial;
            connected(true);
            next();
            new Thread(() -> read(serial)).start();
        } catch (Exception error) { guide.setText("连接失败，请重新连接。"+error.getMessage()); }
    }
    void read(UsbSerialPort serial) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] data = new byte[4096];
        try {
            while (port == serial) {
                int n = serial.read(data,500);
                if(n==0) continue;
                buffer.write(data,0,n);
                String text = buffer.toString("ISO-8859-1");
                int end;
                while ((end=text.indexOf("\r\n\r\n\r\n\r\n"))>=0) {
                    String report = text.substring(0,end);
                    text = text.substring(end+8);
                    if (!report.trim().isEmpty()) {
                        final String complete = new String(report.getBytes("ISO-8859-1"), Charset.forName("GBK"));
                        runOnUiThread(() -> store(complete));
                    }
                }
                buffer.reset();
                buffer.write(text.getBytes("ISO-8859-1"));
                if(buffer.size()>65536) throw new IOException("报告格式无法识别");
            }
        } catch(Exception error) {
            runOnUiThread(() -> { connected(false); guide.setText("设备连接已断开，请重新连接。"); });
        } finally {
            try { serial.close(); } catch(Exception ignored) {}
            if(port==serial) port=null;
        }
    }
    String match(String pattern,String text) throws IOException {
        Matcher m=Pattern.compile(pattern).matcher(text);
        if(!m.find()) throw new IOException("报告字段不完整");
        return m.group(1);
    }
    void store(String text) {
        try {
            String number=match("测试编号\\(No\\.\\):\\s*(\\d+)",text);
            String date=match("检验日期:\\s*(\\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d)",text);
            ArrayList<String> values=new ArrayList<>();
            String unit="",flags="";
            for(String field:fields) {
                Matcher m=Pattern.compile("\\("+Pattern.quote(field)+"\\)\\r?\\n[ \\t]*([<>]?\\d+(?:\\.\\d+)?|HI|LO)([↑↓]?)[ \\t]*(mmol/L|mg/dL)?").matcher(text);
                if(!m.find()) throw new IOException("缺少 "+field);
                values.add(m.group(1));
                if(!m.group(2).isEmpty()) flags+=field+m.group(2)+" ";
                if(!field.equals("TC/HDL")) {
                    if(m.group(3)==null) throw new IOException("缺少单位");
                    if(!unit.isEmpty()&&!unit.equals(m.group(3))) throw new IOException("单位不一致");
                    unit=m.group(3);
                }
            }
            String csv=number+","+date+","+unit+","+String.join(",",values)+","+flags.trim();
            ContentValues row=new ContentValues();
            row.put("report",csv); row.put("date",date); row.put("csv",csv);
            row.put("summary","编号 "+number+" · "+date+"\nTC "+values.get(0)+"   TG "+values.get(1)+"\nHDL "+values.get(2)+"   LDL "+values.get(3)+" "+unit+"\nTC/HDL "+values.get(4)+"  "+flags);
            long id=db.insertWithOnConflict("records",null,row,SQLiteDatabase.CONFLICT_IGNORE);
            refresh();
            guide.setText((id==-1?"记录已存在":"已保存记录")+" "+number+"。可在仪器上选择下一条并打印。");
        } catch(Exception error) { guide.setText("报告解析失败，请重新打印。"+error.getMessage()); }
    }
    void showChart() {
        records.addView(label("LDL 趋势",20,true));
        Spinner modes = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,
            new String[]{"最近 52 条 · 均匀排列","全部记录 · 按时间间隔","最近一年 · 按时间间隔"});
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        modes.setAdapter(adapter);
        modes.setSelection(chartMode);
        records.addView(modes);
        modes.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(AdapterView<?> parent) {}
            public void onItemSelected(AdapterView<?> parent,View view,int position,long id) {
                if(chartMode!=position) { chartMode=position; refresh(); }
            }
        });
        Calendar start = Calendar.getInstance();
        start.add(Calendar.YEAR,-1);
        start.set(Calendar.HOUR_OF_DAY,0); start.set(Calendar.MINUTE,0);
        start.set(Calendar.SECOND,0); start.set(Calendar.MILLISECOND,0);
        long now=System.currentTimeMillis();
        String query=chartMode==0
            ? "SELECT csv FROM (SELECT csv,date,report FROM records ORDER BY date DESC,report DESC LIMIT 52) ORDER BY date ASC,report ASC"
            : "SELECT csv FROM records ORDER BY date ASC,report ASC";
        try(Cursor cursor=db.rawQuery(query,null)) {
            ArrayList<Float> values=new ArrayList<>();
            ArrayList<Long> dates=new ArrayList<>();
            ArrayList<String> labels=new ArrayList<>();
            java.text.SimpleDateFormat format=new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.US);
            format.setLenient(false);
            int skipped=0;
            while(cursor.moveToNext()) {
                String[] row=cursor.getString(0).split(",",-1);
                try {
                    long date=format.parse(row[1]).getTime();
                    if(chartMode==2 && (date<start.getTimeInMillis() || date>now)) continue;
                    float value=Float.NaN;
                    try {
                        value=Float.parseFloat(row[6]);
                        if(!row[2].equals("mmol/L") || value<0 || !Float.isFinite(value)) value=Float.NaN;
                    } catch(Exception ignored) {}
                    if(!Float.isFinite(value)) skipped++;
                    dates.add(date); values.add(value); labels.add(row[1].substring(0,10));
                } catch(Exception ignored) { skipped++; }
            }
            if(dates.isEmpty()) {
                records.addView(label("所选范围暂无记录",14,false));
                return;
            }
            TextView note=label("自定目标 · mmol/L · "+dates.size()+" 次检测",12,false);
            note.setPadding(0,dp(6),0,dp(6)); records.addView(note);
            records.addView(new LdlChart(this,values,dates,labels,chartMode==0),new LinearLayout.LayoutParams(-1,dp(230)));
            if(skipped>0) records.addView(label("部分结果为非数值或其他单位，请在下方记录中查看。",12,false));
        }
    }
    void refresh() {
        records.removeAllViews();
        showChart();
        try(Cursor c=db.rawQuery("SELECT csv, summary FROM records ORDER BY date DESC",null)) {
            TextView count=label("检测记录 · "+c.getCount()+" 次",16,true);
            count.setPadding(0,0,0,dp(14));
            records.addView(count);
            if(c.getCount()==0) records.addView(label("还没有记录\n连接仪器并打印一条历史结果，即可在这里查看。",16,false));
            while(c.moveToNext()) {
                String[] values=c.getString(0).split(",",-1);
                LinearLayout card=new LinearLayout(this);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setPadding(dp(18),dp(18),dp(18),dp(18));
                android.graphics.drawable.GradientDrawable background=new android.graphics.drawable.GradientDrawable();
                background.setColor(0xffffffff); background.setCornerRadius(dp(18));
                card.setBackground(background);
                LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);
                params.bottomMargin=dp(14); records.addView(card,params);
                if(values.length<8) { card.addView(label(c.getString(1),16,false)); continue; }
                card.addView(label(values[1]+"  ·  #"+values[0],14,false));
                String flags=values.length>8 ? values[8] : "";
                metric(card,"LDL · 低密度脂蛋白",values[6],values[2],flags,"LDL",true);
                metric(card,"TC · 总胆固醇",values[3],values[2],flags,"TC",false);
                metric(card,"TG · 甘油三酯",values[4],values[2],flags,"TG",false);
                metric(card,"HDL · 高密度脂蛋白",values[5],values[2],flags,"HDL",false);
                metric(card,"TC / HDL",values[7],"",flags,"TC/HDL",false);
            }
        }
    }
    void metric(LinearLayout card,String name,String value,String unit,String flags,String key,boolean primary) {
        LinearLayout row=new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0,dp(primary?16:10),0,dp(6));
        TextView title=label(name,primary?16:14,primary);
        row.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        String marker=flags.contains(key+"↓")?" ↓":flags.contains(key+"↑")?" ↑":"";
        TextView result=label(value+marker,primary?30:20,true);
        row.addView(result);
        card.addView(row);
        if(primary) {
            TextView units=label(unit,12,false);
            units.setGravity(Gravity.RIGHT);
            card.addView(units);
        } else if(!unit.isEmpty()) {
            TextView units=label(unit,11,false);
            units.setGravity(Gravity.RIGHT); card.addView(units);
        }
    }
    protected void onDestroy() {
        UsbSerialPort serial=port; port=null;
        if(serial!=null) try { serial.close(); } catch(Exception ignored) {}
        unregisterReceiver(receiver);
        super.onDestroy();
    }
}
