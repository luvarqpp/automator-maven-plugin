package com.jamosolutions.automator.help;

import com.jamosolutions.automator.domain.Device;
import com.jamosolutions.automator.domain.TestCase;

import static org.fusesource.jansi.Ansi.ansi;

public class Colorizer {
    public static String colorize(String text) {
        return ansi().render(text).toString();
    }

    public static String device(Device device) {
        return "@|blue,bold " + device.getName() + "|@";
    }
    public static String deviceUdid(Device device) {
        return "@|faint,blue,bold " + device.getUdid() + "|@";
    }
    public static String testCase(TestCase tc) {
        return "@|faint,blue,bold " + tc.getName() + "|@";
    }
}
