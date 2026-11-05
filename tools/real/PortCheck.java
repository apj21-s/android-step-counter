package com.arco.stride;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** StepNet.java total for one whole trace, to compare with tools/ml/check_port.py. Usage: PortCheck <weights.txt | builtin> <trace.csv> */
public class PortCheck {
    public static void main(String[] a) throws Exception {
        StepNet net = a[0].equals("builtin") ? StepNet.standard() : StepNet.fromText(Files.readAllLines(Path.of(a[0])));
        List<String> rows = Files.readAllLines(Path.of(a[1]));
        for (String r : rows) {
            String[] f = r.split(",");
            float gx = Float.parseFloat(f[4]), gy = Float.parseFloat(f[5]), gz = Float.parseFloat(f[6]);
            net.onSample(Float.parseFloat(f[1]), Float.parseFloat(f[2]), Float.parseFloat(f[3]), (float) Math.sqrt(gx * gx + gy * gy + gz * gz), Long.parseLong(f[0]));
        }
        System.out.printf("java    total %.3f%n", net.total);
    }
}
