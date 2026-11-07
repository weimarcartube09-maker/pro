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
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.*;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    // UUID estándar del perfil de puerto serie (SPP)
    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");

    private BluetoothAdapter adapter;
    private BluetoothSocket socket;
    private OutputStream out;
    private volatile boolean connected = false;

    private final List<BluetoothDevice> devices = new ArrayList<>();
    private final StringBuilder csv = new StringBuilder("hora,dato,punto,E,N,Z\n");
    private final SimpleDateFormat hora = new SimpleDateFormat("HH:mm:ss", Locale.US);

    private Spinner spDevices;
    private TextView tvLog, tvStatus;
    private ScrollView scroll;
    private EditText etSend;
    private Button btnConnect;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        spDevices = findViewById(R.id.spDevices);
        tvLog = findViewById(R.id.tvLog);
        tvStatus = findViewById(R.id.tvStatus);
        scroll = findViewById(R.id.scroll);
        etSend = findViewById(R.id.etSend);
        btnConnect = findViewById(R.id.btnConnect);

        adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            tvStatus.setText("Este equipo no tiene Bluetooth");
            return;
        }

        btnConnect.setOnClickListener(v -> { if (connected) disconnect(); else connect(); });
        findViewById(R.id.btnSend).setOnClickListener(v -> send());
        findViewById(R.id.btnStation).setOnClickListener(v -> stationDialog());
        findViewById(R.id.btnTools).setOnClickListener(v ->
                startActivity(new Intent(this, ToolsActivity.class)));
        findViewById(R.id.btnSave).setOnClickListener(v -> saveCsv());
        findViewById(R.id.btnClear).setOnClickListener(v -> {
            tvLog.setText("");
            csv.setLength(0);
            csv.append("hora,dato,punto,E,N,Z\n");
        });

        if (hasPermission()) loadDevices(); else requestPerm();
    }

    // ---------- Permisos ----------
    private boolean hasPermission() {
        return Build.VERSION.SDK_INT < 31 ||
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                        == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPerm() {
        if (Build.VERSION.SDK_INT >= 31)
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 1);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        super.onRequestPermissionsResult(code, p, r);
        if (r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) loadDevices();
        else tvStatus.setText("Falta el permiso de Bluetooth");
    }

    // ---------- Dispositivos emparejados ----------
    private void loadDevices() {
        if (!adapter.isEnabled()) {
            tvStatus.setText("Activa el Bluetooth y reabre la app");
            return;
        }
        devices.clear();
        List<String> names = new ArrayList<>();
        for (BluetoothDevice d : adapter.getBondedDevices()) {
            devices.add(d);
            names.add(d.getName() + "\n" + d.getAddress());
        }
        if (names.isEmpty()) tvStatus.setText("Empareja primero la estación en Ajustes > Bluetooth");
        spDevices.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, names));
    }

    // ---------- Conexión ----------
    private void connect() {
        int pos = spDevices.getSelectedItemPosition();
        if (pos < 0 || pos >= devices.size()) return;
        final BluetoothDevice dev = devices.get(pos);
        tvStatus.setText("Conectando...");
        btnConnect.setEnabled(false);

        new Thread(() -> {
            try {
                socket = dev.createRfcommSocketToServiceRecord(SPP);
                socket.connect();
                out = socket.getOutputStream();
                connected = true;
                runOnUiThread(() -> {
                    tvStatus.setText("Conectado a " + dev.getName());
                    btnConnect.setText("Desconectar");
                    btnConnect.setEnabled(true);
                });
                readLoop(socket.getInputStream());
            } catch (Exception e) {
                runOnUiThread(() -> {
                    tvStatus.setText("Error: " + e.getMessage());
                    btnConnect.setEnabled(true);
                });
            } finally {
                closeQuietly();
            }
        }).start();
    }

    private void readLoop(InputStream in) {
        StringBuilder line = new StringBuilder();
        byte[] buf = new byte[256];
        try {
            int n;
            while (connected && (n = in.read(buf)) > 0) {
                for (int i = 0; i < n; i++) {
                    char c = (char) (buf[i] & 0xFF);
                    if (c == '\n') {
                        String s = line.toString().trim();
                        line.setLength(0);
                        if (!s.isEmpty()) onLine(s);
                    } else if (c != '\r') {
                        line.append(c);
                    }
                }
            }
        } catch (IOException ignored) { }
    }

    private static final Pattern NUM = Pattern.compile("-?\\d+(?:[.,]\\d+)?");

    // Cada línea de la estación llega aquí. Se toman los 3 primeros números como
    // Hz, ángulo cenital V y distancia inclinada. Ajusta esto al formato de tu marca.
    private void onLine(String s) {
        final String t = hora.format(new Date());
        String extra = "";
        String nombre = "", cn = "", ce = "", cz = "";
        Matcher m = NUM.matcher(s);
        List<Double> nums = new ArrayList<>();
        while (m.find()) nums.add(Double.parseDouble(m.group().replace(',', '.')));
        if (nums.size() >= 3) {
            double hz = TopoMath.ang(nums.get(0)), vz = TopoMath.ang(nums.get(1)), sd = nums.get(2);
            double az = Math.toRadians(Store.az0 + hz);
            double hd = sd * Math.sin(Math.toRadians(vz));
            double dz = sd * Math.cos(Math.toRadians(vz)) + Store.hi - Store.ht;
            double n = Store.n0 + hd * Math.cos(az), e = Store.e0 + hd * Math.sin(az), z = Store.z0 + dz;
            nombre = Store.add(n, e, z);
            cn = String.format(Locale.US, "%.3f", n);
            ce = String.format(Locale.US, "%.3f", e);
            cz = String.format(Locale.US, "%.3f", z);
            extra = "\n   → " + nombre + "  E=" + ce + "  N=" + cn + "  Z=" + cz;
        }
        csv.append(t).append(",\"").append(s.replace("\"", "\"\"")).append("\",")
           .append(nombre).append(",").append(ce).append(",").append(cn).append(",").append(cz).append("\n");
        final String linea = t + "  " + s + extra + "\n";
        runOnUiThread(() -> {
            tvLog.append(linea);
            scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    private void stationDialog() {
        ScrollView sv = new ScrollView(this);
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(32, 16, 32, 0);
        sv.addView(l);
        String[] lab = {"Este de la estación", "Norte de la estación", "Cota de la estación",
                "Azimut que corresponde a Hz = 0", "Altura del instrumento", "Altura del prisma"};
        double[] val = {Store.e0, Store.n0, Store.z0, Store.az0, Store.hi, Store.ht};
        final EditText[] ed = new EditText[6];
        for (int i = 0; i < 6; i++) {
            ed[i] = new EditText(this);
            ed[i].setHint(lab[i]);
            ed[i].setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
                    | InputType.TYPE_NUMBER_FLAG_SIGNED);
            ed[i].setText(String.valueOf(val[i]));
            l.addView(ed[i]);
        }
        final CheckBox cb = new CheckBox(this);
        cb.setText("Ángulos en GG.MMSS");
        cb.setChecked(TopoMath.gms);
        l.addView(cb);
        new AlertDialog.Builder(this).setTitle("Datos de la estación").setView(sv)
            .setPositiveButton("Guardar", (d, w) -> {
                try {
                    Store.e0 = Double.parseDouble(ed[0].getText().toString());
                    Store.n0 = Double.parseDouble(ed[1].getText().toString());
                    Store.z0 = Double.parseDouble(ed[2].getText().toString());
                    TopoMath.gms = cb.isChecked();
                    Store.az0 = TopoMath.ang(Double.parseDouble(ed[3].getText().toString()));
                    Store.hi = Double.parseDouble(ed[4].getText().toString());
                    Store.ht = Double.parseDouble(ed[5].getText().toString());
                } catch (NumberFormatException ex) {
                    Toast.makeText(this, "Algún dato no es un número", Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton("Cancelar", null).show();
    }

    private void send() {
        if (!connected || out == null) return;
        final String cmd = etSend.getText().toString();
        new Thread(() -> {
            try {
                out.write((cmd + "\r\n").getBytes());
                out.flush();
            } catch (IOException e) {
                runOnUiThread(() -> tvStatus.setText("Error al enviar"));
            }
        }).start();
        tvLog.append("> " + cmd + "\n");
        etSend.setText("");
    }

    private void disconnect() {
        connected = false;
        closeQuietly();
    }

    private void closeQuietly() {
        connected = false;
        try { if (socket != null) socket.close(); } catch (IOException ignored) { }
        runOnUiThread(() -> {
            btnConnect.setText("Conectar");
            btnConnect.setEnabled(true);
            if (!tvStatus.getText().toString().startsWith("Error"))
                tvStatus.setText("Desconectado");
        });
    }

    // ---------- Exportar ----------
    private void saveCsv() {
        try {
            String name = "topo_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                    .format(new Date()) + ".csv";
            File f = new File(getExternalFilesDir(null), name);
            FileWriter w = new FileWriter(f);
            w.write(csv.toString());
            w.close();
            Toast.makeText(this, "Guardado en:\n" + f.getAbsolutePath(), Toast.LENGTH_LONG).show();
        } catch (IOException e) {
            Toast.makeText(this, "Error al guardar", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        disconnect();
    }
}
