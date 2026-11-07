package com.example.topobt;

import android.Manifest;
import android.app.Activity;
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

public class MainActivity extends Activity {

    // UUID estándar del perfil de puerto serie (SPP)
    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");

    private BluetoothAdapter adapter;
    private BluetoothSocket socket;
    private OutputStream out;
    private volatile boolean connected = false;

    private final List<BluetoothDevice> devices = new ArrayList<>();
    private final StringBuilder csv = new StringBuilder("hora,dato\n");
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
        findViewById(R.id.btnSave).setOnClickListener(v -> saveCsv());
        findViewById(R.id.btnClear).setOnClickListener(v -> {
            tvLog.setText("");
            csv.setLength(0);
            csv.append("hora,dato\n");
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
                adapter.cancelDiscovery();
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
            } catch (IOException e) {
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

    // Aquí se procesa cada línea recibida. Es el sitio para tu parser de la estación.
    private void onLine(String s) {
        final String t = hora.format(new Date());
        csv.append(t).append(",\"").append(s.replace("\"", "\"\"")).append("\"\n");
        runOnUiThread(() -> {
            tvLog.append(t + "  " + s + "\n");
            scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        });
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
