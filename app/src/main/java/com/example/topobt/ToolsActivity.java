package com.example.topobt;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ToolsActivity extends Activity {

    private LinearLayout box;
    private TextView tvRes;
    private final List<EditText> fields = new ArrayList<>();
    private final List<Character> kinds = new ArrayList<>(); // R requerido, A ángulo, Z opcional
    private TopoMath.Prog cur;

    private static final int NUM = InputType.TYPE_CLASS_NUMBER
            | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("Programas de topografía");
        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(16, 16, 16, 16);
        sv.addView(root);

        Spinner sp = new Spinner(this);
        String[] names = new String[TopoMath.PROGS.length];
        for (int i = 0; i < names.length; i++) names[i] = TopoMath.PROGS[i].name;
        sp.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
        root.addView(sp);

        CheckBox cb = new CheckBox(this);
        cb.setText("Ángulos en GG.MMSS (ej. 123.4530 = 123°45'30\")");
        cb.setChecked(TopoMath.gms);
        cb.setOnCheckedChangeListener((v, c) -> TopoMath.gms = c);
        root.addView(cb);

        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        root.addView(box);

        Button calc = new Button(this);
        calc.setText("Calcular");
        calc.setOnClickListener(v -> calcular());
        root.addView(calc);

        tvRes = new TextView(this);
        tvRes.setTypeface(android.graphics.Typeface.MONOSPACE);
        tvRes.setTextIsSelectable(true);
        tvRes.setPadding(0, 16, 0, 16);
        root.addView(tvRes);

        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { build(pos); }
            public void onNothingSelected(AdapterView<?> p) { }
        });
        setContentView(sv);
    }

    private EditText edit(String hint, float weight) {
        EditText e = new EditText(this);
        e.setInputType(NUM);
        e.setHint(hint);
        e.setSingleLine(true);
        if (weight > 0)
            e.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, weight));
        return e;
    }

    private void build(int pos) {
        cur = TopoMath.PROGS[pos];
        box.removeAllViews();
        fields.clear();
        kinds.clear();
        tvRes.setText("");
        for (String spec : cur.in) {
            char t = spec.charAt(0);
            String label = spec.substring(2);
            if (t == 'P') {
                TextView tv = new TextView(this);
                tv.setText(label);
                box.addView(tv);
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                final EditText n = edit("Norte", 1), e = edit("Este", 1), z = edit("Cota", 1);
                row.addView(e); row.addView(n); row.addView(z);
                Button pick = new Button(this);
                pick.setText("Leídos");
                pick.setOnClickListener(v -> elegir(n, e, z));
                row.addView(pick);
                box.addView(row);
                fields.add(n); kinds.add('R');
                fields.add(e); kinds.add('R');
                fields.add(z); kinds.add('Z');
            } else {
                EditText e = edit(label + (t == 'A' ? " (°)" : ""), 0);
                box.addView(e);
                fields.add(e);
                kinds.add(t == 'A' ? 'A' : 'R');
            }
        }
    }

    private void elegir(final EditText n, final EditText e, final EditText z) {
        if (Store.points.isEmpty()) {
            Toast.makeText(this, "Aún no hay puntos leídos de la estación", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] items = new String[Store.points.size()];
        for (int i = 0; i < items.length; i++) {
            double[] q = Store.points.get(i);
            items[i] = String.format(Locale.US, "%s  E=%.3f N=%.3f Z=%.3f",
                    Store.names.get(i), q[1], q[0], q[2]);
        }
        new AlertDialog.Builder(this).setTitle("Puntos leídos").setItems(items, (d, i) -> {
            double[] q = Store.points.get(i);
            n.setText(String.format(Locale.US, "%.3f", q[0]));
            e.setText(String.format(Locale.US, "%.3f", q[1]));
            z.setText(String.format(Locale.US, "%.3f", q[2]));
        }).show();
    }

    private void calcular() {
        double[] v = new double[fields.size()];
        for (int i = 0; i < v.length; i++) {
            String s = fields.get(i).getText().toString().trim().replace(',', '.');
            char k = kinds.get(i);
            if (s.isEmpty() || s.equals("-") || s.equals(".")) {
                if (k == 'Z') { v[i] = Double.NaN; continue; }
                tvRes.setText("Faltan datos por llenar.");
                return;
            }
            try {
                v[i] = Double.parseDouble(s);
            } catch (NumberFormatException ex) {
                tvRes.setText("Número no válido: " + s);
                return;
            }
            if (k == 'A') v[i] = TopoMath.ang(v[i]);
        }
        try {
            tvRes.setText(cur.run.run(v));
        } catch (Exception ex) {
            tvRes.setText("Error de cálculo: " + ex.getMessage());
        }
    }
}
