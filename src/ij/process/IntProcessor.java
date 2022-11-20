package ij.process;

import java.util.*;
import java.awt.*;
import java.awt.image.*;


/** This is an extended ColorProcessor that supports signed 32-bit int images. */
public class IntProcessor extends ColorProcessor {
	private byte[] pixels8;

	/**Creates a blank IntProcessor with the specified dimensions. */
	public IntProcessor(int width, int height) {
		this(width, height, new int[width*height]);
	}

	/**Creates an IntProcessor from a pixel array. */
	public IntProcessor(int width, int height, int[] pixels) {
		super(width, height, pixels);
		makeDefaultColorModel();
	}

	/** Returns this image as an 8-bit BufferedImage . */
	public BufferedImage getBufferedImage() {
		return convertToByte(true).getBufferedImage();
	}
	
	@Override
	public void setColorModel(ColorModel cm) {
		if (cm!=null && !(cm instanceof IndexColorModel))
			throw new IllegalArgumentException("IndexColorModel required");
		if (cm!=null && cm instanceof LUT)
			cm = ((LUT)cm).getColorModel();
		this.cm = cm;
		baseCM = null;
		rLUT1 = rLUT2 = null;
		inversionTested = false;
		minThreshold = NO_THRESHOLD;
	}
	
	@Override
	public float getPixelValue(int x, int y) {
		if (x>=0 && x<width && y>=0 && y<height)
			return (float)pixels[y*width+x];
		else 
			return Float.NaN;
	}

	/** Returns the number of channels (1). */
	@Override
	public int getNChannels() {
		return 1;
	}
	
	public void findMinAndMax() {
		int size = width*height;
		int value;
		int min = pixels[0];
		int max = pixels[0];
		for (int i=1; i<size; i++) {
			value = pixels[i];
			if (value<min)
				min = value;
			else if (value>max)
				max = value;
		}
		this.min = min;
		this.max = max;
		minMaxSet = true;
	}

	@Override
	public void resetMinAndMax() {
		findMinAndMax();
		resetThreshold();
	}
	
	@Override
	public void setMinAndMax(double minimum, double maximum, int channels) {
		min = (int)minimum;
		max = (int)maximum;
		minMaxSet = true;
		resetThreshold();
	}

}


