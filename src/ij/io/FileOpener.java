package ij.io;
import java.awt.*;
import java.awt.image.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import ij.gui.*;
import ij.process.*;
import ij.measure.*;
import ij.*;
import ij.plugin.frame.*;

/**
 * Opens or reverts an image specified by a FileInfo object. Images can
 * be loaded from either a file (directory+fileName) or a URL (url+fileName).
 * Here is an example:	
 * <pre>
 *   public class FileInfo_Test implements PlugIn {
 *     public void run(String arg) {
 *       FileInfo fi = new FileInfo();
 *       fi.width = 256;
 *       fi.height = 254;
 *       fi.offset = 768;
 *       fi.fileName = "blobs.tif";
 *       fi.directory = "/Users/wayne/Desktop/";
 *       new FileOpener(fi).open();
 *     }  
 *   }	
 * </pre> 
 */
public class FileOpener {

	private FileInfo fi;
	private int width;
	private int height;
	private static boolean showConflictMessage = true;
	private double minValue;
	private double maxValue;
	private static boolean silentMode;

	public FileOpener(FileInfo fi) {
		this.fi = fi;
		if (fi!=null) {
			width = fi.getWidth();
			height = fi.getHeight();
		}
		if (IJ.debugMode) IJ.log("FileInfo: "+fi);
	}
	
	/** Opens the image and returns it has an ImagePlus object. */
	public ImagePlus openImage() {
		boolean wasRecording = Recorder.record;
		Recorder.record = false;
		ImagePlus imp = open(false);
		Recorder.record = wasRecording;
		return imp;
	}

	/** Opens the image and displays it. */
	public void open() {
		open(true);
	}
	
	/** Obsolete, replaced by openImage() and open(). */
	public ImagePlus open(boolean show) {

		ImagePlus imp=null;
		Object pixels;
		ImageProcessor ip;
		
		ColorModel cm = createColorModel(fi);
		if (fi.getnImages()>1)
			return openStack(cm, show);
		switch (fi.getFileType()) {
			case FileInfo.BITMAP:
				pixels = readPixels(fi);
				if (pixels==null) return null;
				ip = new ByteProcessor(width, height, (byte[])pixels, cm);
    			imp = new ImagePlus(fi.getFileName(), ip);
				break;
			case FileInfo.GRAY12_UNSIGNED:
				pixels = readPixels(fi);
				if (pixels==null) return null;
	    		ip = new ShortProcessor(width, height, (short[])pixels, cm);
       			imp = new ImagePlus(fi.getFileName(), ip);
				break;
			case FileInfo.GRAY64_FLOAT:
				pixels = readPixels(fi);
				if (pixels==null) return null;
	    		ip = new FloatProcessor(width, height, (float[])pixels, cm);
       			imp = new ImagePlus(fi.getFileName(), ip);
				break;
			case FileInfo.CMYK:
				pixels = readPixels(fi);
				if (pixels==null) return null;
				ip = new ColorProcessor(width, height, (int[])pixels);
				if (fi.getFileType()==FileInfo.CMYK)
					ip.invert();
				imp = new ImagePlus(fi.getFileName(), ip);
				break;
			case FileInfo.RGB48_PLANAR:
				boolean planar = fi.getFileType()==FileInfo.RGB48_PLANAR;
				Object[] pixelArray = (Object[])readPixels(fi);
				if (pixelArray==null) return null;
				int nChannels = 3;
				ImageStack stack = new ImageStack(width, height);
				nChannels = setStackProperties(pixelArray, nChannels, stack);
        		imp = new ImagePlus(fi.getFileName(), stack);
        		imp = setFinalDimensions(imp, planar, nChannels);
				break;
			default:
		}
		imp.setFileInfo(fi);
		setCalibration(imp);
		setPropertiesFileOpener(show, imp);
		return imp;
	}

	private void setPropertiesFileOpener(boolean show, ImagePlus imp) {
		if (fi.getInfo()!=null)
			imp.setProperty("Info", fi.getInfo());
		if (fi.getSliceLabels()!=null&&fi.getSliceLabels().length==1&&fi.getSliceLabels()[0]!=null)
			imp.setProp("Slice_Label", fi.getSliceLabels()[0]);
		if (fi.getPlot()!=null) try {
			Plot plot = new Plot(imp, new ByteArrayInputStream(fi.getPlot()));
			imp.setProperty(Plot.PROPERTY_KEY, plot);
		} catch (Exception e) { IJ.handleException(e); }
		if (fi.getRoi()!=null)
			decodeAndSetRoi(imp, fi);
		if (fi.getOverlay()!=null)
			setOverlay(imp, fi.getOverlay());
		if (fi.getProperties()!=null)
			imp.setProperties(fi.getProperties());
		if (show) imp.show();
	}

	private ImagePlus setFinalDimensions(ImagePlus imp, boolean planar, int nChannels) {
		imp.setDimensions(nChannels, 1, 1);
		if (planar)
			imp.getProcessor().resetMinAndMax();
		imp.setFileInfo(fi);
		int mode = IJ.COMPOSITE;
		mode = descriptionSetting(mode);
		imp = new CompositeImage(imp, mode);
		setPoditionsImage(imp, planar, nChannels);
		if (fi.isWhiteIsZero()) // cmyk?
			IJ.run(imp, "Invert", "");
		return imp;
	}

	private int descriptionSetting(int mode) {
		if (fi.getDescription()!=null) {
			if (fi.getDescription().indexOf("mode=color")!=-1)
			mode = IJ.COLOR;
			else if (fi.getDescription().indexOf("mode=gray")!=-1)
			mode = IJ.GRAYSCALE;
		}
		return mode;
	}

	private void setPoditionsImage(ImagePlus imp, boolean planar, int nChannels) {
		if (!planar && fi.getDisplayRanges()==null) {
			if (nChannels==4)
				((CompositeImage)imp).resetDisplayRanges();
			else {
				for (int c=1; c<=3; c++) {
					imp.setPosition(c, 1, 1);
					imp.setDisplayRange(minValue, maxValue);
				}
				imp.setPosition(1, 1, 1);
			}
		}
	}

	private int setStackProperties(Object[] pixelArray, int nChannels, ImageStack stack) {
		stack.addSlice("Red", pixelArray[0]);
		stack.addSlice("Green", pixelArray[1]);
		stack.addSlice("Blue", pixelArray[2]);
		if (fi.getSamplesPerPixel()==4 && pixelArray.length==4) {
			stack.addSlice("Gray", pixelArray[3]);
			nChannels = 4;
		}
		return nChannels;
	}
	
	public ImageProcessor openProcessor() {
		Object pixels;
		ProgressBar pb=null;
		ImageProcessor ip = null;		
		ColorModel cm = createColorModel(fi);
		switch (fi.getFileType()) {
			case FileInfo.GRAY8:
			case FileInfo.COLOR8:
			case FileInfo.BITMAP:
				pixels = readPixels(fi);
				if (pixels==null) return null;
				ip = new ByteProcessor(width, height, (byte[])pixels, cm);
				break;
			case FileInfo.GRAY16_SIGNED:
			case FileInfo.GRAY16_UNSIGNED:
			case FileInfo.GRAY12_UNSIGNED:
				pixels = readPixels(fi);
				if (pixels==null) return null;
	    		ip = new ShortProcessor(width, height, (short[])pixels, cm);
				break;
			case FileInfo.GRAY32_INT:
			case FileInfo.GRAY32_UNSIGNED:
			case FileInfo.GRAY32_FLOAT:
			case FileInfo.GRAY24_UNSIGNED:
			case FileInfo.GRAY64_FLOAT:
				pixels = readPixels(fi);
				if (pixels==null) return null;
	    		ip = new FloatProcessor(width, height, (float[])pixels, cm);
				break;
			case FileInfo.RGB:
			case FileInfo.BGR:
			case FileInfo.ARGB:
			case FileInfo.ABGR:
			case FileInfo.BARG:
			case FileInfo.RGB_PLANAR:
			case FileInfo.CMYK:
				pixels = readPixels(fi);
				if (pixels==null) return null;
				ip = new ColorProcessor(width, height, (int[])pixels);
				if (fi.getFileType()==FileInfo.CMYK)
					ip.invert();
				break;
		}
		return ip;
	}

	void setOverlay(ImagePlus imp, byte[][] rois) {
		Overlay overlay = new Overlay();
		Overlay proto = null;
		for (int i=0; i<rois.length; i++) {
			Roi roi = RoiDecoder.openFromByteArray(rois[i]);
			if (roi==null)
				continue;
			if (proto==null) {
				proto = roi.getPrototypeOverlay();
				overlay.drawLabels(proto.getDrawLabels());
				overlay.drawNames(proto.getDrawNames());
				overlay.drawBackgrounds(proto.getDrawBackgrounds());
				overlay.setLabelColor(proto.getLabelColor());
				overlay.setLabelFont(proto.getLabelFont(), proto.scalableLabels());
			}
			overlay.add(roi);
		}
		imp.setOverlay(overlay);
	}

	/** Opens a stack of images. */
	ImagePlus openStack(ColorModel cm, boolean show) {
		ImageStack stack = new ImageStack(fi.getWidth(), fi.getHeight(), cm);
		long skip = fi.getOffset();
		Object pixels;
		try {
			ImageReader reader = new ImageReader(fi);
			InputStream is = createInputStream(fi);
			if (is==null)
				return null;
			IJ.resetEscape();
			for (int i=1; i<=fi.getnImages(); i++) {
				if (!silentMode)
					IJ.showStatus("Reading: " + i + "/" + fi.getnImages());
				if (IJ.escapePressed()) {
					IJ.beep();
					IJ.showProgress(1.0);
					silentMode = false;
					return null;
				}
				pixels = reader.readPixels(is, skip);
				if (pixels==null)
					break;
				stack.addSlice(null, pixels);
				skip = fi.getGap();
				if (!silentMode)
					IJ.showProgress(i, fi.getnImages());
			}
			is.close();
		}
		catch (Exception e) {
			IJ.log("" + e);
		}
		catch(OutOfMemoryError e) {
			IJ.outOfMemory(fi.getFileName());
			stack.trim();
		}
		if (!silentMode) IJ.showProgress(1.0);
		if (stack.size()==0)
			return null;
		if (fi.getSliceLabels()!=null && fi.getSliceLabels().length<=stack.size()) {
			for (int i=0; i<fi.getSliceLabels().length; i++)
				stack.setSliceLabel(fi.getSliceLabels()[i], i+1);
		}
		ImagePlus imp = new ImagePlus(fi.getFileName(), stack);
		if (fi.getInfo()!=null)
			imp.setProperty("Info", fi.getInfo());
		if (fi.getRoi()!=null)
			decodeAndSetRoi(imp, fi);
		if (fi.getOverlay()!=null)
			setOverlay(imp, fi.getOverlay());
		if (fi.getProperties()!=null)
			imp.setProperties(fi.getProperties());
		if (show) imp.show();
		imp.setFileInfo(fi);
		setCalibration(imp);
		ImageProcessor ip = imp.getProcessor();
		if (ip.getMin()==ip.getMax())  // find stack min and max if first slice is blank
			setStackDisplayRange(imp);
		if (!silentMode) IJ.showProgress(1.0);
		return imp;
	}
	
	private void decodeAndSetRoi(ImagePlus imp, FileInfo fi) {
		Roi roi = RoiDecoder.openFromByteArray(fi.getRoi());
		imp.setRoi(roi);
		if ((roi instanceof PointRoi) && ((PointRoi)roi).getNCounters()>1) 
			IJ.setTool("multi-point");
	}

	void setStackDisplayRange(ImagePlus imp) {
		ImageStack stack = imp.getStack();
		double min = Double.MAX_VALUE;
		double max = -Double.MAX_VALUE;
		int n = stack.size();
		for (int i=1; i<=n; i++) {
			if (!silentMode)
				IJ.showStatus("Calculating stack min and max: "+i+"/"+n);
			ImageProcessor ip = stack.getProcessor(i);
			ip.resetMinAndMax();
			if (ip.getMin()<min)
				min = ip.getMin();
			if (ip.getMax()>max)
				max = ip.getMax();
		}
		imp.getProcessor().setMinAndMax(min, max);
		imp.updateAndDraw();
	}
	
	/** Restores the original version of the specified image. */
	public void revertToSaved(ImagePlus imp) {
		if (fi==null)
			return;
		String path = fi.getFilePath();
		if (fi.getUrl()!=null && !fi.getUrl().equals("") && (fi.getDirectory()==null||fi.getDirectory().equals("")))
			path = fi.getUrl();
		IJ.showStatus("Loading: " + path);
		ImagePlus imp2 = null;
		if (!path.endsWith(".raw"))
			imp2 = IJ.openImage(path);
		if (imp2!=null)
			imp.setImage(imp2);
		else {
			if (fi.getnImages()>1)
				return;
			Object pixels = readPixels(fi);
			if (pixels==null) return;
			ColorModel cm = createColorModel(fi);
			ImageProcessor ip = null;
			switch (fi.getFileType()) {
				case FileInfo.GRAY8:
				case FileInfo.COLOR8:
				case FileInfo.BITMAP:
					ip = new ByteProcessor(width, height, (byte[])pixels, cm);
					imp.setProcessor(null, ip);
					break;
				case FileInfo.GRAY16_SIGNED:
				case FileInfo.GRAY16_UNSIGNED:
				case FileInfo.GRAY12_UNSIGNED:
					ip = new ShortProcessor(width, height, (short[])pixels, cm);
					imp.setProcessor(null, ip);
					break;
				case FileInfo.GRAY32_INT:
				case FileInfo.GRAY32_FLOAT:
					ip = new FloatProcessor(width, height, (float[])pixels, cm);
					imp.setProcessor(null, ip);
					break;
				case FileInfo.RGB:
				case FileInfo.BGR:
				case FileInfo.ARGB:
				case FileInfo.ABGR:
				case FileInfo.RGB_PLANAR:
					Image img = Toolkit.getDefaultToolkit().createImage(new MemoryImageSource(width, height, (int[])pixels, 0, width));
					imp.setImage(img);
					break;
				case FileInfo.CMYK:
					ip = new ColorProcessor(width, height, (int[])pixels);
					ip.invert();
					imp.setProcessor(null, ip);
					break;
			}
		}
	}
	
	void setCalibration(ImagePlus imp) {
		if (fi.getFileType()==FileInfo.GRAY16_SIGNED) {
			if (IJ.debugMode) IJ.log("16-bit signed");
 			imp.getLocalCalibration().setSigned16BitCalibration();
		}
		Properties props = decodeDescriptionString(fi);
		Calibration cal = imp.getCalibration();
		boolean calibrated = false;
		if (fi.getPixelWidth()>0.0 && fi.getUnit()!=null) {
			double threshold = 0.001;
			if (fi.getDescription()!=null && fi.getDescription().startsWith("ImageJ"))
				threshold = 0.0001;
			if (Prefs.convertToMicrons && fi.getPixelWidth()<=threshold && fi.getUnit().equals("cm")) {
				fi.setPixelWidth(fi.getPixelWidth() * 10000.0);
				fi.setPixelHeight(fi.getPixelHeight() * 10000.0);
				if (fi.getPixelDepth()!=1.0)
					fi.setPixelDepth(fi.getPixelDepth() * 10000.0);
				fi.setUnit("um");
			}
			cal.pixelWidth = fi.getPixelWidth();
			cal.pixelHeight = fi.getPixelHeight();
			cal.pixelDepth = fi.getPixelDepth();
			cal.setUnit(fi.getUnit());
			calibrated = true;
		}
		
		if (fi.getValueUnit()!=null) {
			if (imp.getBitDepth()==32)
				cal.setValueUnit(fi.getValueUnit());
			else {
				int f = fi.getCalibrationFunction();
				if ((f>=Calibration.STRAIGHT_LINE && f<=Calibration.EXP_RECOVERY && fi.getCoefficients()!=null)
				|| f==Calibration.UNCALIBRATED_OD) {
					boolean zeroClip = props!=null && props.getProperty("zeroclip", "false").equals("true");	
					cal.setFunction(f, fi.getCoefficients(), fi.getValueUnit(), zeroClip);
					calibrated = true;
				}
			}
		}
		
		if (calibrated)
			checkForCalibrationConflict(imp, cal);
		
		if (fi.getFrameInterval()!=0.0)
			cal.frameInterval = fi.getFrameInterval();
		
		if (props==null)
			return;
					
		cal.xOrigin = getDouble(props,"xorigin");
		cal.yOrigin = getDouble(props,"yorigin");
		cal.zOrigin = getDouble(props,"zorigin");
		cal.setInvertY(getBoolean(props, "inverty"));
		cal.info = props.getProperty("info");		
				
		cal.fps = getDouble(props,"fps");
		cal.loop = getBoolean(props, "loop");
		cal.frameInterval = getDouble(props,"finterval");
		cal.setTimeUnit(props.getProperty("tunit", "sec"));
		cal.setYUnit(props.getProperty("yunit"));
		cal.setZUnit(props.getProperty("zunit"));

		double displayMin = getDouble(props,"min");
		double displayMax = getDouble(props,"max");
		if (!(displayMin==0.0&&displayMax==0.0)) {
			int type = imp.getType();
			ImageProcessor ip = imp.getProcessor();
			if (type==ImagePlus.GRAY8 || type==ImagePlus.COLOR_256)
				ip.setMinAndMax(displayMin, displayMax);
			else if (type==ImagePlus.GRAY16 || type==ImagePlus.GRAY32) {
				if (ip.getMin()!=displayMin || ip.getMax()!=displayMax)
					ip.setMinAndMax(displayMin, displayMax);
			}
		}
		
		if (getBoolean(props, "8bitcolor"))
			imp.setTypeToColor256(); // set type to COLOR_256
		
		int stackSize = imp.getStackSize();
		if (stackSize>1) {
			int channels = (int)getDouble(props,"channels");
			int slices = (int)getDouble(props,"slices");
			int frames = (int)getDouble(props,"frames");
			if (channels==0) channels = 1;
			if (slices==0) slices = 1;
			if (frames==0) frames = 1;
			//IJ.log("setCalibration: "+channels+"  "+slices+"  "+frames);
			if (channels*slices*frames==stackSize) {
				imp.setDimensions(channels, slices, frames);
				if (getBoolean(props, "hyperstack"))
					imp.setOpenAsHyperStack(true);
			}
		}
	}

		
	void checkForCalibrationConflict(ImagePlus imp, Calibration cal) {
		Calibration gcal = imp.getGlobalCalibration();
		if  (gcal==null || !showConflictMessage || IJ.isMacro())
			return;
		if (cal.pixelWidth==gcal.pixelWidth && cal.getUnit().equals(gcal.getUnit()))
			return;
		GenericDialog gd = new GenericDialog(imp.getTitle());
		gd.addMessage("The calibration of this image conflicts\nwith the current global calibration.");
		gd.addCheckbox("Disable_Global Calibration", true);
		gd.addCheckbox("Disable_these Messages", false);
		gd.showDialog();
		if (gd.wasCanceled()) return;
		boolean disable = gd.getNextBoolean();
		if (disable) {
			imp.setGlobalCalibration(null);
			imp.setCalibration(cal);
			WindowManager.repaintImageWindows();
		}
		boolean dontShow = gd.getNextBoolean();
		if (dontShow) showConflictMessage = false;
	}

	/** Returns an IndexColorModel for the image specified by this FileInfo. */
	public ColorModel createColorModel(FileInfo fi) {
		if (fi.getLutSize()>0)
			return new IndexColorModel(8, fi.getLutSize(), fi.getReds(), fi.getGreens(), fi.getBlues());
		else
			return LookUpTable.createGrayscaleColorModel(fi.isWhiteIsZero());
	}

	/** Returns an InputStream for the image described by this FileInfo. */
	public InputStream createInputStream(FileInfo fi) throws IOException, MalformedURLException {
		InputStream is = null;
		boolean gzip = fi.getFileName()!=null && (fi.getFileName().endsWith(".gz")||fi.getFileName().endsWith(".GZ"));
		if (fi.getInputStream()!=null)
			is = fi.getInputStream();
		else if (fi.getUrl()!=null && !fi.getUrl().equals(""))
			is = new URL(fi.getUrl()+fi.getFileName()).openStream();
		else {
			if (fi.getDirectory()!=null && fi.getDirectory().length()>0 && !(fi.getDirectory().endsWith(Prefs.separator)||fi.getDirectory().endsWith("/")))
				fi.setDirectory(fi.getDirectory() + Prefs.separator);
		    File f = new File(fi.getFilePath());
		    if (gzip) fi.setCompression(FileInfo.COMPRESSION_UNKNOWN);
		    if (f==null || !f.exists() || f.isDirectory() || !validateFileInfo(f, fi))
		    	is = null;
		    else
				is = new FileInputStream(f);
		}
		if (is!=null) {
			if (fi.getCompression()>=FileInfo.LZW)
				is = new RandomAccessStream(is);
			else if (gzip)
				is = new GZIPInputStream(is, 50000);
		}
		return is;
	}
	
	static boolean validateFileInfo(File f, FileInfo fi) {
		long offset = fi.getOffset();
		long length = 0;
		if (fi.getWidth()<=0 || fi.getHeight()<=0) {
		   error("Width or height <= 0.", fi, offset, length);
		   return false;
		}
		if (offset>=0 && offset<1000L)
			 return true;
		if (offset<0L) {
		   error("Offset is negative.", fi, offset, length);
		   return false;
		}
		if (fi.getFileType()==FileInfo.BITMAP || fi.getCompression()!=FileInfo.COMPRESSION_NONE)
			return true;
		length = f.length();
		long size = fi.getWidth()*fi.getHeight()*fi.getBytesPerPixel();
		size = fi.getnImages()>1?size:size/4;
		if (fi.getHeight()==1) size = 0; // allows plugins to read info of unknown length at end of file
		if (offset+size>length) {
		   error("Offset + image size > file length.", fi, offset, length);
		   return false;
		}
		return true;
	}

	static void error(String msg, FileInfo fi, long offset, long length) {
		String msg2 = "FileInfo parameter error. \n"
			+msg + "\n \n"
			+"  Width: " + fi.getWidth() + "\n"
			+"  Height: " + fi.getHeight() + "\n"
			+"  Offset: " + offset + "\n"
			+"  Bytes/pixel: " + fi.getBytesPerPixel() + "\n"
			+(length>0?"  File length: " + length + "\n":"");
		if (silentMode) {
			IJ.log("Error opening "+fi.getFilePath());
			IJ.log(msg2);
		} else
			IJ.error("FileOpener", msg2);
	}


	/** Reads the pixel data from an image described by a FileInfo object. */
	Object readPixels(FileInfo fi) {
		Object pixels = null;
		try {
			InputStream is = createInputStream(fi);
			if (is==null)
				return null;
			ImageReader reader = new ImageReader(fi);
			pixels = reader.readPixels(is);
			minValue = reader.min;
			maxValue = reader.max;
			is.close();
		}
		catch (Exception e) {
			if (!Macro.MACRO_CANCELED.equals(e.getMessage()))
				IJ.handleException(e);
		}
		return pixels;
	}

	public Properties decodeDescriptionString(FileInfo fi) {
		if (fi.getDescription()==null || fi.getDescription().length()<7)
			return null;
		if (IJ.debugMode)
			IJ.log("Image Description: " + new String(fi.getDescription()).replace('\n',' '));
		if (!fi.getDescription().startsWith("ImageJ"))
			return null;
		Properties props = new Properties();
		InputStream is = new ByteArrayInputStream(fi.getDescription().getBytes());
		try {props.load(is); is.close();}
		catch (IOException e) {return null;}
		String dsUnit = props.getProperty("unit","");
		if ("cm".equals(fi.getUnit()) && "um".equals(dsUnit)) {
			fi.setPixelWidth(fi.getPixelWidth() * 10000);
			fi.setPixelHeight(fi.getPixelHeight() * 10000);
		}
		fi.setUnit(dsUnit);
		Double n = getNumber(props,"cf");
		if (n!=null) fi.setCalibrationFunction(n.intValue());
		double c[] = new double[5];
		int count = 0;
		for (int i=0; i<5; i++) {
			n = getNumber(props,"c"+i);
			if (n==null) break;
			c[i] = n.doubleValue();
			count++;
		}
		if (count>=2) {
			fi.setCoefficients(new double[count]);
			for (int i=0; i<count; i++)
				fi.getCoefficients()[i] = c[i];			
		}
		fi.setValueUnit(props.getProperty("vunit"));
		n = getNumber(props,"images");
		if (n!=null && n.doubleValue()>1.0)
		fi.setnImages((int)n.doubleValue());
		n = getNumber(props, "spacing");
		if (n!=null) {
			double spacing = n.doubleValue();
			if (spacing<0) spacing = -spacing;
			fi.setPixelDepth(spacing);
		}
		String name = props.getProperty("name");
		if (name!=null)
			fi.setFileName(name);
		return props;
	}

	private Double getNumber(Properties props, String key) {
		String s = props.getProperty(key);
		if (s!=null) {
			try {
				return Double.valueOf(s);
			} catch (NumberFormatException e) {}
		}	
		return null;
	}
	
	private double getDouble(Properties props, String key) {
		Double n = getNumber(props, key);
		return n!=null?n.doubleValue():0.0;
	}
	
	private boolean getBoolean(Properties props, String key) {
		String s = props.getProperty(key);
		return s!=null&&s.equals("true")?true:false;
	}
	
	public static void setShowConflictMessage(boolean b) {
		showConflictMessage = b;
	}
	
	static void setSilentMode(boolean mode) {
		silentMode = mode;
	}


}
