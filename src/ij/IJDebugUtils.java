package ij;

import ij.io.LogStream;

public class IJDebugUtils {

	/** Use setDebugMode(boolean) to enable/disable debug mode. */
	public static boolean debugMode;
	
	/**Enable/disable debug mode.*/
	public static void setDebugMode(boolean b) {
		debugMode = b;
		LogStream.redirectSystem(debugMode);
	}
}
