package com.jamosolutions.automator.help;

import com.jamosolutions.automator.domain.Device;
import com.jamosolutions.automator.domain.TestCase;

import static org.fusesource.jansi.Ansi.ansi;

public class Colorizer {
    public static String colorize(String text) {
        return ansi().render(text).toString();
    }

    /**
     * Return colorized string with device name.
     *
     * @param device device instance to be used for returned string
     * @return currently blue and bold anotated ascii string. i.e. <code>@|blue,bold deviceName|@</code> for example.
     */
    public static String device(Device device) {
        return "@|blue,bold " + device.getName() + "|@";
    }

    /**
     * Return colorized string with device name.
     */
    public static String deviceUdid(Device device) {
        return "@|faint,blue,bold " + device.getUdid() + "|@";
    }

    /**
     * Return colorized string with device name.
     */
    public static String testCase(TestCase tc) {
        return "@|faint,blue,bold " + tc.getName() + "|@";
    }
}
