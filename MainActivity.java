package com.example.topobt;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.text.InputType;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private static final UUID SPP=UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");
    private static final int EXPORT_TXT=71;
    private BluetoothAdapter adapter; private BluetoothSocket socket; private OutputStream out;
    private volatile boolean connected=false, saveNext=false, measurementPending=false;
    private boolean autoOnConnect=true, autoRunning=false;
    private long measurementSentAt=0;
    private final Handler uiHandler=new Handler(Looper.getMainLooper());
    private final Runnable autoTick= new Runnable(){@Override public void run(){if(!autoRunning||!connected)return;
        if(!measurementPending||System.currentTimeMillis()-measurementSentAt>8000)requestMeasurement(false);
        uiHandler.postDelayed(this,4000);
    }};
    private final List<BluetoothDevice> devices=new ArrayList<>();
    private final StringBuilder csv=new StringBuilder("hora,dato,punto,E,N,Z\n");
    private final SimpleDateFormat hora=new SimpleDateFormat("HH:mm:ss",Locale.US);
    private Spinner spDevices,spProfile; private TextView tvLog,tvStatus,tvLive; private ScrollView scroll;
    private EditText etSend; private Button btnConnect,btnAuto,btnTarget; private StationView stationView;
    private File rootDir, activeFile;
    private final StringBuilder gsiBuffer=new StringBuilder();
    private static final String[] PROFILES={"Automático (detectar equipo)","Sokkia CX (2 vías)","Topcon GM (2 vías)","Topcon ES (2 vías)","Leica TS / TCRM (GSI)","Comando manual"};
    private volatile int activeProfile=1;

    @Override protected void onCreate(Bundle b){super.onCreate(b);setContentView(R.layout.activity_main);
        spDevices=findViewById(R.id.spDevices);spProfile=findViewById(R.id.spProfile);tvLog=findViewById(R.id.tvLog);
        tvStatus=findViewById(R.id.tvStatus);tvLive=findViewById(R.id.tvLive);scroll=findViewById(R.id.scroll);
        etSend=findViewById(R.id.etSend);btnConnect=findViewById(R.id.btnConnect);btnAuto=findViewById(R.id.btnAuto);btnTarget=findViewById(R.id.btnTarget);stationView=findViewById(R.id.stationView);
        spProfile.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,PROFILES));
        adapter=BluetoothAdapter.getDefaultAdapter();
        if(adapter==null){tvStatus.setText("Este equipo no tiene Bluetooth");return;}
        rootDir=new File(getExternalFilesDir(null),"TopoBT");rootDir.mkdirs();autoOnConnect=getPreferences(MODE_PRIVATE).getBoolean("auto",true);updateAutoButton();String savedFolder=getPreferences(MODE_PRIVATE).getString("folder",Store.folder);setFolder(savedFolder);
        btnConnect.setOnClickListener(v->{if(connected)disconnect();else connect();});
        findViewById(R.id.btnSend).setOnClickListener(v->send());
        findViewById(R.id.btnStation).setOnClickListener(v->stationDialog());
        findViewById(R.id.btnTools).setOnClickListener(v->startActivity(new Intent(this,ToolsActivity.class)));
        findViewById(R.id.btnMeasure).setOnClickListener(v->requestMeasurement(false));
        findViewById(R.id.btnMeasureSave).setOnClickListener(v->requestMeasurement(true));
        btnTarget.setOnClickListener(v->targetDialog());updateTargetButton();
        btnAuto.setOnClickListener(v->{autoOnConnect=!autoOnConnect;getPreferences(MODE_PRIVATE).edit().putBoolean("auto",autoOnConnect).apply();if(autoOnConnect&&connected)startAuto();else stopAuto();updateAutoButton();});
        findViewById(R.id.btnFolder).setOnClickListener(v->folderDialog());
        findViewById(R.id.btnClear).setOnClickListener(v->tvLog.setText(""));
        Store.measureAction=()->requestMeasurement(false);
        Store.saveMeasureAction=()->requestMeasurement(true);
        if(hasPermission())loadDevices();else requestPerm();
    }

    private boolean hasPermission(){return Build.VERSION.SDK_INT<31||checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED;}
    private void requestPerm(){if(Build.VERSION.SDK_INT>=31)requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT},1);}
    @Override public void onRequestPermissionsResult(int c,String[] p,int[] r){super.onRequestPermissionsResult(c,p,r);if(r.length>0&&r[0]==PackageManager.PERMISSION_GRANTED)loadDevices();else tvStatus.setText("Falta el permiso de Bluetooth");}
    private void loadDevices(){if(!adapter.isEnabled()){tvStatus.setText("Activa Bluetooth y vuelve a abrir la app");return;}devices.clear();List<String> names=new ArrayList<>();
        for(BluetoothDevice d:adapter.getBondedDevices()){devices.add(d);names.add(d.getName()+"\n"+d.getAddress());}
        if(names.isEmpty())tvStatus.setText("Empareja primero la estación en Ajustes > Bluetooth");
        spDevices.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,names));}
    private int resolveProfile(int selected,String deviceName){if(selected>0)return selected;String n=deviceName==null?"":deviceName.toUpperCase(Locale.ROOT);
        if(n.contains("LEICA")||n.contains("TCRM")||n.startsWith("TS"))return 4;
        if(n.contains("SOKKIA")||n.contains("CX"))return 1;
        if(n.contains("ES-" )||n.contains("TOPCON ES"))return 3;
        if(n.contains("GM-")||n.contains("TOPCON GM")||n.contains("TOPCON"))return 2;
        return 1;
    }
    private void connect(){int pos=spDevices.getSelectedItemPosition();if(pos<0||pos>=devices.size())return;BluetoothDevice dev=devices.get(pos);final int selectedProfile=spProfile.getSelectedItemPosition();tvStatus.setText("Conectando...");btnConnect.setEnabled(false);
        new Thread(()->{try{socket=dev.createRfcommSocketToServiceRecord(SPP);socket.connect();out=socket.getOutputStream();connected=true;
            activeProfile=resolveProfile(selectedProfile,dev.getName());
            final String profileName=PROFILES[activeProfile];
            runOnUiThread(()->{tvStatus.setText("Conectado · "+dev.getName()+" · "+profileName);btnConnect.setText("Desconectar");btnConnect.setEnabled(true);if(autoOnConnect)startAuto();});readLoop(socket.getInputStream());
        }catch(Exception e){runOnUiThread(()->{tvStatus.setText("Error de conexión: "+e.getMessage());btnConnect.setEnabled(true);});}finally{closeQuietly();}}).start();}
    private void readLoop(InputStream in){StringBuilder line=new StringBuilder();byte[] b=new byte[256];try{int n;while(connected&&(n=in.read(b))>0)for(int i=0;i<n;i++){int x=b[i]&255;char c=(char)x;
            if(c=='\n'){String s=line.toString().trim();line.setLength(0);if(!s.isEmpty())onLine(s);}else if(c!='\r'&&x>=32)line.append(c);
        }}catch(IOException ignored){}}

    private void requestMeasurement(boolean save){if(!connected||out==null){Toast.makeText(this,"Conecta primero la estación",Toast.LENGTH_SHORT).show();return;}
        if(measurementPending&&System.currentTimeMillis()-measurementSentAt<8000){if(save)saveNext=true;return;}
        int profile=activeProfile;
        if(profile==5){Toast.makeText(this,"El perfil manual no inicia mediciones.",Toast.LENGTH_LONG).show();return;}
        if(profile==4)gsiBuffer.setLength(0);
        saveNext=save;measurementPending=true;measurementSentAt=System.currentTimeMillis();tvStatus.setText(save?"Midiendo; se guardará al recibir respuesta…":(autoRunning?"Lectura automática en curso…":"Solicitando medición…"));
        final byte[] command=profile<=3?new byte[]{0x11,'\r','\n'}:"GET/M/WI21/WI22/WI31\r\n".getBytes(StandardCharsets.US_ASCII);
        new Thread(()->{try{out.write(command);out.flush();}catch(IOException e){saveNext=false;runOnUiThread(()->tvStatus.setText("Error al solicitar medición"));}}).start();
    }
    private void startAuto(){if(!connected||!autoOnConnect)return;autoRunning=true;updateAutoButton();requestMeasurement(false);uiHandler.removeCallbacks(autoTick);uiHandler.postDelayed(autoTick,4000);}
    private void stopAuto(){autoRunning=false;uiHandler.removeCallbacks(autoTick);updateAutoButton();}
    private void updateAutoButton(){if(btnAuto!=null)btnAuto.setText(autoOnConnect?(autoRunning?"Pausar lectura automática":"Lectura automática: activada"):("Activar lectura automática"));}
    private void onLine(String s){String t=hora.format(new Date());int profile=activeProfile;
        double sd=Double.NaN,vz=Double.NaN,hz=Double.NaN;
        if(profile>=1&&profile<=3){double[] q=parseSokkia(s);if(q!=null){sd=q[0];vz=q[1];hz=q[2];}}
        else if(profile==4){if(gsiBuffer.length()>0)gsiBuffer.append(' ');gsiBuffer.append(s);double[] q=parseGsi(gsiBuffer.toString());if(q==null){if(gsiBuffer.length()>512)gsiBuffer.setLength(0);tvLog.append(t+"  "+s+"\n");return;}s=gsiBuffer.toString().trim();gsiBuffer.setLength(0);hz=q[0];vz=q[1];sd=q[2];}
        String name="",ce="",cn="",cz="",extra="";
        if(!Double.isNaN(sd)&&!Double.isNaN(vz)&&!Double.isNaN(hz)&&sd>0){measurementPending=false;
            Store.lastSd=sd;Store.lastHz=hz;Store.lastVz=vz;
            boolean stationReady=finite(Store.n0)&&finite(Store.e0)&&finite(Store.z0)&&finite(Store.az0);
            if(stationReady){
                double az=Math.toRadians(Store.az0+hz),hd=sd*Math.sin(Math.toRadians(vz)),dz=sd*Math.cos(Math.toRadians(vz))+Store.hi-Store.ht;
                double n=Store.n0+hd*Math.cos(az),e=Store.e0+hd*Math.sin(az),z=Store.z0+dz;
                Store.lastN=n;Store.lastE=e;Store.lastZ=z;name=Store.add(n,e,z);Store.lastPointName=name;ce=fmt(e);cn=fmt(n);cz=fmt(z);
                if(Store.pointListener!=null){Store.PointListener listener=Store.pointListener;Store.pointListener=null;double[] point={n,e,z};runOnUiThread(()->listener.onPoint(point));}
                extra="\n   → "+name+"  E="+ce+"  N="+cn+"  Z="+cz;
                if(saveNext){persistPoint(t,s,name,e,n,z);saveNext=false;}
                final String live=String.format(Locale.US,"D: %.3f m   |   Azimut Hz: %.4f°   |   V: %.4f°\nE: %.3f   |   N: %.3f   |   Z: %.3f",sd,hz,vz,e,n,z);
                runOnUiThread(()->{tvLive.setText(live);stationView.invalidate();tvStatus.setText("Medición recibida");});
            }else{
                Store.lastN=Store.lastE=Store.lastZ=Double.NaN;
                Store.pointListener=null;
                if(saveNext){persistRawObservation(t,s);saveNext=false;runOnUiThread(()->Toast.makeText(this,"Lectura guardada. Configura la estación cuando necesites E/N/Z.",Toast.LENGTH_LONG).show());}
                final String live=String.format(Locale.US,"D: %.3f m   |   Azimut Hz: %.4f°   |   V: %.4f°\nE: —   |   N: —   |   Z: —",sd,hz,vz);
                runOnUiThread(()->{tvLive.setText(live);stationView.invalidate();tvStatus.setText("Lectura recibida; falta configurar Estación");});
            }
        }else runOnUiThread(()->tvStatus.setText("Respuesta recibida; formato sin interpretar"));
        csv.append(t).append(",\"").append(s.replace("\"","\"\"")).append("\",").append(name).append(",").append(ce).append(",").append(cn).append(",").append(cz).append("\n");
        final String outLine=t+"  "+s+extra+"\n";runOnUiThread(()->{tvLog.append(outLine);scroll.post(()->scroll.fullScroll(View.FOCUS_DOWN));});
    }
    private double[] parseGsi(String s){double hz=Double.NaN,vz=Double.NaN,sd=Double.NaN;
        Matcher m=Pattern.compile("(21|22|31)[.\\w]{0,4}([+-])(\\d{1,16})").matcher(s);
        while(m.find()){int wi=Integer.parseInt(m.group(1));double val;try{val=Double.parseDouble(m.group(3));}catch(Exception ex){continue;}if("-".equals(m.group(2)))val=-val;
            if(wi==21)hz=gsiAngle(val);else if(wi==22)vz=gsiAngle(val);else if(wi==31)sd=val/1000.0;
        }
        return Double.isNaN(hz)||Double.isNaN(vz)||Double.isNaN(sd)?null:new double[]{hz,vz,sd};}
    private double[] parseSokkia(String s){String[] f=s.trim().replace('_',' ').split("\\s+");if(f.length<3)return null;try{
        double d=fixedNumber(f[0],true),v=fixedNumber(f[1],false),h=fixedNumber(f[2],false);
        if(d<=0)return null;return new double[]{d,v,h};
    }catch(Exception e){return null;}}
    private double fixedNumber(String token,boolean distance){String t=token.replaceAll("[^0-9.+-]","");boolean neg=t.startsWith("-");String digits=t.replaceAll("[^0-9]","");
        if(t.contains(".")){double x=Double.parseDouble(t);return distance?x:(x<0?-dmsToDegrees(Double.toString(Math.abs(x))):dmsToDegrees(Double.toString(Math.abs(x))));}
        if(distance){double x=Double.parseDouble(t);return x/1000.0;}
        if(digits.length()<6)return Double.parseDouble(t);
        int n=digits.length(),deg=Integer.parseInt(digits.substring(0,n-4)),min=Integer.parseInt(digits.substring(n-4,n-2));
        double sec=Double.parseDouble(digits.substring(n-2));double x=deg+min/60.0+sec/3600.0;return neg?-x:x;
    }
    private double dmsToDegrees(String x){double a=Double.parseDouble(x);int d=(int)a;double tail=(a-d)*100;int m=(int)tail;double sec=(tail-m)*100;return d+m/60.0+sec/3600.0;}
    private double gsiAngle(double raw){long v=Math.round(Math.abs(raw));double deg=v/100000.0;int d=(int)deg;long tail=v-(long)d*100000;int min=(int)(tail/1000);double sec=(tail-min*1000)/10.0;double dec=d+min/60.0+sec/3600.0;return raw<0?-dec:dec;}
    private String fmt(double v){return String.format(Locale.US,"%.3f",v);}
    private boolean finite(double v){return !Double.isNaN(v)&&!Double.isInfinite(v);}

    private void stationDialog(){ScrollView sv=new ScrollView(this);LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);l.setPadding(32,16,32,0);sv.addView(l);
        String[] labels={"Este de la estación","Norte de la estación","Cota de la estación","Azimut de Hz=0","Altura del instrumento","Altura del prisma"};double[] vals={Store.e0,Store.n0,Store.z0,Store.az0,Store.hi,Store.ht};EditText[] ed=new EditText[6];
        for(int i=0;i<6;i++){ed[i]=new EditText(this);ed[i].setHint(labels[i]);ed[i].setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL|InputType.TYPE_NUMBER_FLAG_SIGNED);ed[i].setText(Double.isNaN(vals[i])?"":String.valueOf(vals[i]));l.addView(ed[i]);}
        CheckBox cb=new CheckBox(this);cb.setText("Ángulos en GG.MMSS");cb.setChecked(TopoMath.gms);l.addView(cb);
        new AlertDialog.Builder(this).setTitle("Datos de estación (se calculan con la lectura; completar estación y orientación)").setView(sv).setPositiveButton("Guardar",(d,w)->{try{
            Store.e0=val(ed[0]);Store.n0=val(ed[1]);Store.z0=val(ed[2]);Store.az0=TopoMath.ang(val(ed[3]));Store.hi=val(ed[4]);Store.ht=Store.reflectorless?0:val(ed[5]);TopoMath.gms=cb.isChecked();updateTargetButton();
        }catch(Exception ex){Toast.makeText(this,"Revisa los datos numéricos",Toast.LENGTH_SHORT).show();}}).setNegativeButton("Cancelar",null).show();}
    private void targetDialog(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);l.setPadding(32,16,32,0);
        RadioGroup rg=new RadioGroup(this);RadioButton prism=new RadioButton(this),noPrism=new RadioButton(this);prism.setText("Prisma");noPrism.setText("Sin prisma");rg.addView(prism);rg.addView(noPrism);l.addView(rg);
        EditText height=new EditText(this);height.setHint("Altura del prisma (m)");height.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL|InputType.TYPE_NUMBER_FLAG_SIGNED);height.setText(String.valueOf(Store.reflectorless?0:Store.ht));l.addView(height);
        prism.setChecked(!Store.reflectorless);noPrism.setChecked(Store.reflectorless);height.setEnabled(!Store.reflectorless);
        rg.setOnCheckedChangeListener((g,id)->height.setEnabled(id==prism.getId()));
        TextView note=new TextView(this);note.setText("La altura y el objetivo se aplican a los cálculos de la app. El modo EDM de la estación puede requerir cambio en el equipo según el modelo.");l.addView(note);
        new AlertDialog.Builder(this).setTitle("Objetivo y altura").setView(l).setPositiveButton("Aplicar",(d,w)->{try{Store.reflectorless=noPrism.isChecked();Store.ht=Store.reflectorless?0:Double.parseDouble(height.getText().toString().trim().replace(',','.'));updateTargetButton();}catch(Exception ex){Toast.makeText(this,"Ingresa una altura válida",Toast.LENGTH_SHORT).show();}}).setNegativeButton("Cancelar",null).show();}
    private void updateTargetButton(){if(btnTarget!=null)btnTarget.setText(Store.reflectorless?"Sin prisma":"Prisma · H="+fmt(Store.ht)+" m");}
    private double val(EditText e){return Double.parseDouble(e.getText().toString().trim().replace(',','.'));}
    private void send(){if(!connected||out==null){Toast.makeText(this,"Conecta primero la estación",Toast.LENGTH_SHORT).show();return;}String cmd=etSend.getText().toString();
        new Thread(()->{try{out.write((cmd+"\r\n").getBytes(StandardCharsets.UTF_8));out.flush();}catch(IOException e){runOnUiThread(()->tvStatus.setText("Error al enviar"));}}).start();tvLog.append("> "+cmd+"\n");etSend.setText("");}
    private void disconnect(){connected=false;stopAuto();closeQuietly();}
    private void closeQuietly(){connected=false;autoRunning=false;uiHandler.removeCallbacks(autoTick);try{if(socket!=null)socket.close();}catch(IOException ignored){}runOnUiThread(()->{updateAutoButton();btnConnect.setText("Conectar");btnConnect.setEnabled(true);if(!tvStatus.getText().toString().startsWith("Error"))tvStatus.setText("Desconectado");});}

    private void setFolder(String name){if(name==null||name.trim().isEmpty())name="Levantamiento";Store.folder=name.replaceAll("[^A-Za-z0-9 _-]","_");getPreferences(MODE_PRIVATE).edit().putString("folder",Store.folder).apply();File d=new File(rootDir,Store.folder);d.mkdirs();activeFile=new File(d,"puntos.txt");loadFolderPoints();}
    private void loadFolderPoints(){Store.points.clear();Store.names.clear();if(!activeFile.exists())return;try(BufferedReader r=new BufferedReader(new FileReader(activeFile))){String line;boolean first=true;while((line=r.readLine())!=null){if(first){first=false;continue;}String[] a=line.split(";",-1);if(a.length<5)continue;try{double e=Double.parseDouble(a[2]),n=Double.parseDouble(a[3]),z=Double.parseDouble(a[4]);Store.names.add(a[1]);Store.points.add(new double[]{n,e,z});}catch(Exception ignored){}}}catch(IOException ignored){}}
    private void folderDialog(){File[] dirs=rootDir.listFiles(File::isDirectory);List<String> names=new ArrayList<>();if(dirs!=null)for(File f:dirs)names.add(f.getName());int newFolderIndex=names.size();names.add("+ Crear carpeta nueva");int exportIndex=names.size();names.add("Exportar TXT de la carpeta actual");String[] a=names.toArray(new String[0]);
        new AlertDialog.Builder(this).setTitle("Archivos · carpeta actual: "+Store.folder).setItems(a,(d,i)->{if(i==newFolderIndex)newFolder();else if(i==exportIndex)exportTxt();else{setFolder(a[i]);Toast.makeText(this,"Carpeta: "+Store.folder,Toast.LENGTH_SHORT).show();}}).show();}
    private void newFolder(){EditText e=new EditText(this);e.setHint("Nombre de carpeta");new AlertDialog.Builder(this).setTitle("Nueva carpeta").setView(e).setPositiveButton("Crear",(d,w)->{setFolder(e.getText().toString());Toast.makeText(this,"Carpeta creada: "+Store.folder,Toast.LENGTH_SHORT).show();}).setNegativeButton("Cancelar",null).show();}
    private void persistPoint(String time,String raw,String name,double east,double north,double z){try{if(!activeFile.exists())try(FileWriter fw=new FileWriter(activeFile,true)){fw.write("hora;punto;E;N;Z;lectura\n");}
        try(FileWriter fw=new FileWriter(activeFile,true)){fw.write(time+";"+name+";"+fmt(east)+";"+fmt(north)+";"+fmt(z)+";"+raw.replace(';',',')+"\n");}
        runOnUiThread(()->Toast.makeText(this,"Guardado en "+Store.folder+"/puntos.txt",Toast.LENGTH_SHORT).show());
    }catch(IOException e){runOnUiThread(()->Toast.makeText(this,"No se pudo guardar el punto",Toast.LENGTH_SHORT).show());}}
    private void persistRawObservation(String time,String raw){try{if(!activeFile.exists())try(FileWriter fw=new FileWriter(activeFile,true)){fw.write("hora;punto;E;N;Z;lectura\n");}try(FileWriter fw=new FileWriter(activeFile,true)){fw.write(time+";;;;;"+raw.replace(';',',')+"\n");}}catch(IOException e){runOnUiThread(()->Toast.makeText(this,"No se pudo guardar la lectura",Toast.LENGTH_SHORT).show());}}
    private void exportTxt(){if(!activeFile.exists()||activeFile.length()==0){Toast.makeText(this,"La carpeta no tiene puntos guardados todavía",Toast.LENGTH_SHORT).show();return;}Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("text/plain");i.putExtra(Intent.EXTRA_TITLE,Store.folder+"_puntos.txt");startActivityForResult(i,EXPORT_TXT);}
    @Override protected void onActivityResult(int req,int res,Intent data){super.onActivityResult(req,res,data);if(req==EXPORT_TXT&&res==RESULT_OK&&data!=null){try(InputStream in=new FileInputStream(activeFile);OutputStream o=getContentResolver().openOutputStream(data.getData())){if(o==null)throw new IOException("No se pudo crear archivo");byte[] b=new byte[4096];int n;while((n=in.read(b))>0)o.write(b,0,n);Toast.makeText(this,"TXT exportado",Toast.LENGTH_LONG).show();}catch(Exception e){Toast.makeText(this,"No se pudo exportar: "+e.getMessage(),Toast.LENGTH_LONG).show();}}}
    @Override protected void onDestroy(){super.onDestroy();disconnect();}
}
