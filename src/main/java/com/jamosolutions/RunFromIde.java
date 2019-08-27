package com.jamosolutions;

import com.jamosolutions.automator.JamoAutomatorMojo;
import org.apache.maven.monitor.logging.DefaultLog;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.codehaus.plexus.logging.Logger;
import org.codehaus.plexus.logging.console.ConsoleLogger;
import org.fusesource.jansi.AnsiConsole;

public class RunFromIde {
    private static final Log log = new DefaultLog(new ConsoleLogger(Logger.LEVEL_DEBUG, "RunFromIde"));

    public static void main(String[] args) {
        JamoAutomatorMojo jamoAutomatorMojo = new JamoAutomatorMojo(true);
        try {
            // make InteliJ Idea console output colors
            System.setProperty("jansi.passthrough", "true");
            AnsiConsole.systemInstall();

            jamoAutomatorMojo.setLog((Log) log);
            jamoAutomatorMojo.execute();
        } catch (MojoExecutionException e) {
            e.printStackTrace();
        } finally {
            AnsiConsole.systemUninstall();
        }
    }
}
