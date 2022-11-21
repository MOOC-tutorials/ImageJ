package ij.io;
import ij.util.Tools;
import ij.IJ;
import java.io.*;
import java.util.*;
import java.net.*;

/**
Decodes single and multi-image TIFF files. The LZW decompression
code was contributed by Curtis Rueden.
*/
public class TiffDecoder {

	// tags
	public static final int NEW_SUBFILE_TYPE = 254;
	public static final int IMAGE_WIDTH = 256;
	public static final int IMAGE_LENGTH = 257;
	public static final int BITS_PER_SAMPLE = 258;
	public static final int COMPRESSION = 259;
	public static final int PHOTO_INTERP = 262;
	public static final int IMAGE_DESCRIPTION = 270;
	public static final int STRIP_OFFSETS = 273;
	public static final int ORIENTATION = 274;
	public static final int SAMPLES_PER_PIXEL = 277;
	public static final int ROWS_PER_STRIP = 278;
	public static final int STRIP_BYTE_COUNT = 279;
	public static final int X_RESOLUTION = 282;
	public static final int Y_RESOLUTION = 283;
	public static final int PLANAR_CONFIGURATION = 284;
	public static final int RESOLUTION_UNIT = 296;
	public static final int SOFTWARE = 305;
	public static final int DATE_TIME = 306;
	public static final int ARTIST = 315;
	public static final int HOST_COMPUTER = 316;
	public static final int PREDICTOR = 317;
	public static final int COLOR_MAP = 320;
	public static final int TILE_WIDTH = 322;
	public static final int SAMPLE_FORMAT = 339;
	public static final int JPEG_TABLES = 347;
	public static final int METAMORPH1 = 33628;
	public static final int METAMORPH2 = 33629;
	public static final int IPLAB = 34122;
	public static final int NIH_IMAGE_HDR = 43314;
	public static final int META_DATA_BYTE_COUNTS = 50838; // private tag registered with Adobe
	public static final int META_DATA = 50839; // private tag registered with Adobe
	
	//constants
	static final int UNSIGNED = 1;
	static final int SIGNED = 2;
	static final int FLOATING_POINT = 3;

	//field types
	static final int SHORT = 3;
	static final int LONG = 4;

	// metadata types
	static final int MAGIC_NUMBER = 0x494a494a;  // "IJIJ"
	static final int INFO = 0x696e666f;  // "info" (Info image property)
	static final int LABELS = 0x6c61626c;  // "labl" (slice labels)
	static final int RANGES = 0x72616e67;  // "rang" (display ranges)
	static final int LUTS = 0x6c757473;    // "luts" (channel LUTs)
	static final int PLOT = 0x706c6f74;    // "plot" (serialized plot)
	static final int ROI = 0x726f6920;     // "roi " (ROI)
	static final int OVERLAY = 0x6f766572; // "over" (overlay)
	static final int PROPERTIES = 0x70726f70; // "prop" (properties)
	
	private String directory;
	private String name;
	private String url;
	protected RandomAccessStream in;
	protected boolean debugMode;
	private boolean littleEndian;
	private String dInfo;
	private int ifdCount;
	private int[] metaDataCounts;
	private String tiffMetadata;
	private int photoInterp;
		
	public TiffDecoder(String directory, String name) {
		if (directory==null)
			directory = "";
		directory = IJ.addSeparator(directory);
		this.directory = directory;
		this.name = name;
	}

	public TiffDecoder(InputStream in, String name) {
		directory = "";
		this.name = name;
		url = "";
		this.in = new RandomAccessStream(in);
	}

	final int getInt() throws IOException {
		int b1 = in.read();
		int b2 = in.read();
		int b3 = in.read();
		int b4 = in.read();
		if (littleEndian)
			return ((b4 << 24) + (b3 << 16) + (b2 << 8) + (b1 << 0));
		else
			return ((b1 << 24) + (b2 << 16) + (b3 << 8) + b4);
	}
	
	final long getUnsignedInt() throws IOException {
		return (long)getInt()&0xffffffffL;
	}

	final int getShort() throws IOException {
		int b1 = in.read();
		int b2 = in.read();
		if (littleEndian)
			return ((b2<<8) + b1);
		else
			return ((b1<<8) + b2);
	}

    final long readLong() throws IOException {
    	if (littleEndian)
        	return ((long)getInt()&0xffffffffL) + ((long)getInt()<<32);
        else
			return ((long)getInt()<<32) + ((long)getInt()&0xffffffffL);
        	//return in.read()+(in.read()<<8)+(in.read()<<16)+(in.read()<<24)+(in.read()<<32)+(in.read()<<40)+(in.read()<<48)+(in.read()<<56);
    }

    final double readDouble() throws IOException {
        return Double.longBitsToDouble(readLong());
    }

	long OpenImageFileHeader() throws IOException {
	// Open 8-byte Image File Header at start of file.
	// Returns the offset in bytes to the first IFD or -1
	// if this is not a valid tiff file.
		int byteOrder = in.readShort();
		if (byteOrder==0x4949) // "II"
			littleEndian = true;
		else if (byteOrder==0x4d4d) // "MM"
			littleEndian = false;
		else {
			in.close();
			return -1;
		}
		int magicNumber = getShort(); // 42
		long offset = ((long)getInt())&0xffffffffL;
		return offset;
	}
		
	int getValue(int fieldType, int count) throws IOException {
		int value = 0;
		int unused;
		if (fieldType==SHORT && count==1) {
			value = getShort();
			unused = getShort();
		} else
			value = getInt();
		return value;
	}	
	
	void getColorMap(long offset, FileInfo fi) throws IOException {
		byte[] colorTable16 = new byte[768*2];
		long saveLoc = in.getLongFilePointer();
		in.seek(offset);
		in.readFully(colorTable16);
		in.seek(saveLoc);
		fi.setLutSize(256);
		fi.setReds(new byte[256]);
		fi.setGreens(new byte[256]);
		fi.setBlues(new byte[256]);
		int j = 0;
		if (littleEndian) j++;
		int sum = 0;
		for (int i=0; i<256; i++) {
			fi.getReds()[i] = colorTable16[j];
			sum += fi.getReds()[i];
			fi.getGreens()[i] = colorTable16[512+j];
			sum += fi.getGreens()[i];
			fi.getBlues()[i] = colorTable16[1024+j];
			sum += fi.getBlues()[i];
			j += 2;
		}
		if (sum!=0 && fi.getFileType()==FileInfo.GRAY8)
			fi.setFileType(FileInfo.COLOR8);
	}
	
	byte[] getString(int count, long offset) throws IOException {
		count--; // skip null byte at end of string
		if (count<=3)
			return null;
		byte[] bytes = new byte[count];
		long saveLoc = in.getLongFilePointer();
		in.seek(offset);
		in.readFully(bytes);
		in.seek(saveLoc);
		return bytes;
	}

	/** Save the image description in the specified FileInfo. ImageJ
		saves spatial and density calibration data in this string. For
		stacks, it also saves the number of images to avoid having to
		decode an IFD for each image. */
	public void saveImageDescription(byte[] description, FileInfo fi) {
        String id = new String(description);
        boolean createdByImageJ = id.startsWith("ImageJ");
        if (!createdByImageJ)
			saveMetadata(getName(IMAGE_DESCRIPTION), id);
		if (id.length()<7) return;
		fi.setDescription(id);
        int index1 = id.indexOf("images=");
        if (index1>0 && createdByImageJ && id.charAt(7)!='\n') {
            int index2 = id.indexOf("\n", index1);
            if (index2>0) {
                String images = id.substring(index1+7,index2);
                int n = (int)Tools.parseDouble(images, 0.0);
                if (n>1 && fi.getCompression()==FileInfo.COMPRESSION_NONE)
                	fi.setnImages(n);
            }
        }
	}

	public void saveMetadata(String name, String data) {
		if (data==null) return;
        String str = name+": "+data+"\n";
        if (tiffMetadata==null)
        	tiffMetadata = str;
        else
        	tiffMetadata += str;
	}

	void decodeNIHImageHeader(int offset, FileInfo fi) throws IOException {
		long saveLoc = in.getLongFilePointer();
		
		in.seek(offset+12);
		int version = in.readShort();
		
		in.seek(offset+160);
		double scale = in.readDouble();
		if (version>106 && scale!=0.0) {
			fi.setPixelWidth(1.0/scale);
			fi.setPixelHeight(fi.getPixelWidth());
		} 

		// spatial calibration
		in.seek(offset+172);
		int units = in.readShort();
		if (version<=153) units += 5;
		switch (units) {
			case 5: fi.setUnit("nanometer"); break;
			case 6: fi.setUnit("micrometer"); break;
			case 7: fi.setUnit("mm"); break;
			case 8: fi.setUnit("cm"); break;
			case 9: fi.setUnit("meter"); break;
			case 10: fi.setUnit("km"); break;
			case 11: fi.setUnit("inch"); break;
			case 12: fi.setUnit("ft"); break;
			case 13: fi.setUnit("mi"); break;
		}

		// density calibration
		in.seek(offset+182);
		int fitType = in.read();
		int unused = in.read();
		int nCoefficients = in.readShort();
		if (fitType==11) {
			fi.setCalibrationFunction(21); //Calibration.UNCALIBRATED_OD
			fi.setValueUnit("U. OD");
		} else if (fitType>=0 && fitType<=8 && nCoefficients>=1 && nCoefficients<=5) {
			switch (fitType) {
				case 0: fi.setCalibrationFunction(0); break; //Calibration.STRAIGHT_LINE
				case 1: fi.setCalibrationFunction(1); break; //Calibration.POLY2
				case 2: fi.setCalibrationFunction(2); break; //Calibration.POLY3
				case 3: fi.setCalibrationFunction(3); break; //Calibration.POLY4
				case 5: fi.setCalibrationFunction(4); break; //Calibration.EXPONENTIAL
				case 6: fi.setCalibrationFunction(5); break; //Calibration.POWER
				case 7: fi.setCalibrationFunction(6); break; //Calibration.LOG
				case 8: fi.setCalibrationFunction(10); break; //Calibration.RODBARD2 (NIH Image)
			}
			fi.setCoefficients(new double[nCoefficients]);
			for (int i=0; i<nCoefficients; i++) {
				fi.getCoefficients()[i]=in.readDouble();
			}
			in.seek(offset+234);
			int size = in.read();
			StringBuffer sb = new StringBuffer();
			if (size>=1 && size<=16) {
				for (int i=0; i<size; i++)
					sb.append((char)(in.read()));
				fi.setValueUnit(new String(sb));
			} else
				fi.setValueUnit(" ");
		}
			
		in.seek(offset+260);
		int nImages = in.readShort();
		if (nImages>=2 && (fi.getFileType()==FileInfo.GRAY8||fi.getFileType()==FileInfo.COLOR8)) {
			fi.setnImages(nImages);
			fi.setPixelDepth(in.readFloat());	//SliceSpacing
			int skip = in.readShort();		//CurrentSlice
			fi.setFrameInterval(in.readFloat());
		}
			
		in.seek(offset+272);
		float aspectRatio = in.readFloat();
		if (version>140 && aspectRatio!=0.0)
			fi.setPixelHeight(fi.getPixelWidth()/aspectRatio);
		
		in.seek(saveLoc);
	}
	
	void dumpTag(int tag, int count, int value, FileInfo fi) {
		long lvalue = ((long)value)&0xffffffffL;
		String name = getName(tag);
		String cs = (count==1)?"":", count=" + count;
		dInfo += "    " + tag + ", \"" + name + "\", value=" + lvalue + cs + "\n";
		//ij.IJ.log(tag + ", \"" + name + "\", value=" + value + cs + "\n");
	}

	String getName(int tag) {
		String name;
		switch (tag) {
			case NEW_SUBFILE_TYPE: name="NewSubfileType"; break;
			case IMAGE_WIDTH: name="ImageWidth"; break;
			case IMAGE_LENGTH: name="ImageLength"; break;
			case STRIP_OFFSETS: name="StripOffsets"; break;
			case ORIENTATION: name="Orientation"; break;
			case PHOTO_INTERP: name="PhotoInterp"; break;
			case IMAGE_DESCRIPTION: name="ImageDescription"; break;
			case BITS_PER_SAMPLE: name="BitsPerSample"; break;
			case SAMPLES_PER_PIXEL: name="SamplesPerPixel"; break;
			case ROWS_PER_STRIP: name="RowsPerStrip"; break;
			case STRIP_BYTE_COUNT: name="StripByteCount"; break;
			case X_RESOLUTION: name="XResolution"; break;
			case Y_RESOLUTION: name="YResolution"; break;
			case RESOLUTION_UNIT: name="ResolutionUnit"; break;
			case SOFTWARE: name="Software"; break;
			case DATE_TIME: name="DateTime"; break;
			case ARTIST: name="Artist"; break;
			case HOST_COMPUTER: name="HostComputer"; break;
			case PLANAR_CONFIGURATION: name="PlanarConfiguration"; break;
			case COMPRESSION: name="Compression"; break; 
			case PREDICTOR: name="Predictor"; break; 
			case COLOR_MAP: name="ColorMap"; break; 
			case SAMPLE_FORMAT: name="SampleFormat"; break; 
			case JPEG_TABLES: name="JPEGTables"; break; 
			case NIH_IMAGE_HDR: name="NIHImageHeader"; break; 
			case META_DATA_BYTE_COUNTS: name="MetaDataByteCounts"; break; 
			case META_DATA: name="MetaData"; break; 
			default: name="???"; break;
		}
		return name;
	}

	double getRational(long loc) throws IOException {
		long saveLoc = in.getLongFilePointer();
		in.seek(loc);
		double numerator = getUnsignedInt();
		double denominator = getUnsignedInt();
		in.seek(saveLoc);
		if (denominator!=0.0)
			return numerator/denominator;
		else
			return 0.0;
	}
	
	FileInfo OpenIFD() throws IOException {
	// Get Image File Directory data
		int tag, fieldType, count, value;
		int nEntries = getShort();
		if (nEntries<1 || nEntries>1000)
			return null;
		ifdCount++;
		if ((ifdCount%50)==0 && ifdCount>0)
			ij.IJ.showStatus("Opening IFDs: "+ifdCount);
		FileInfo fi = new FileInfo();
		fi.setFileType(FileInfo.BITMAP);  //BitsPerSample defaults to 1
		for (int i=0; i<nEntries; i++) {
			tag = getShort();
			fieldType = getShort();
			count = getInt();
			value = getValue(fieldType, count);
			long lvalue = ((long)value)&0xffffffffL;
			if (debugMode && ifdCount<10) dumpTag(tag, count, value, fi);
			switch (tag) {
				case IMAGE_WIDTH: 
					fi.setWidth(value);
					fi.setIntelByteOrder(littleEndian);
					break;
				case IMAGE_LENGTH: 
					fi.setHeight(value);
					break;
 				case STRIP_OFFSETS:
					if (count==1)
						fi.setStripOffsets(new int[] {value});
					else {
						long saveLoc = in.getLongFilePointer();
						in.seek(lvalue);
						fi.setStripOffsets(new int[count]);
						for (int c=0; c<count; c++)
							fi.getStripOffsets()[c] = getInt();
						in.seek(saveLoc);
					}
					fi.setOffset(count>0?fi.getStripOffsets()[0]:value);
					if (count>1 && (((long)fi.getStripOffsets()[count-1])&0xffffffffL)<(((long)fi.getStripOffsets()[0])&0xffffffffL))
						fi.setOffset(fi.getStripOffsets()[count-1]);
					break;
				case STRIP_BYTE_COUNT:
					if (count==1)
						fi.setStripLengths(new int[] {value});
					else {
						long saveLoc = in.getLongFilePointer();
						in.seek(lvalue);
						fi.setStripLengths(new int[count]);
						for (int c=0; c<count; c++) {
							if (fieldType==SHORT)
								fi.getStripLengths()[c] = getShort();
							else
								fi.getStripLengths()[c] = getInt();
						}
						in.seek(saveLoc);
					}
					break;
 				case PHOTO_INTERP:
 					photoInterp = value;
 					fi.setWhiteIsZero(value==0);
					break;
				case BITS_PER_SAMPLE:
						if (count==1) {
							if (value==8)
								fi.setFileType(FileInfo.GRAY8);
							else if (value==16)
								fi.setFileType(FileInfo.GRAY16_UNSIGNED);
							else if (value==32)
								fi.setFileType(FileInfo.GRAY32_INT);
							else if (value==12)
								fi.setFileType(FileInfo.GRAY12_UNSIGNED);
							else if (value==1)
								fi.setFileType(FileInfo.BITMAP);
							else
								error("Unsupported BitsPerSample: " + value);
						} else if (count>1) {
							long saveLoc = in.getLongFilePointer();
							in.seek(lvalue);
							int bitDepth = getShort();
							if (bitDepth==8)
								fi.setFileType(FileInfo.GRAY8);
							else if (bitDepth==16)
								fi.setFileType(FileInfo.GRAY16_UNSIGNED);
							else
								error("ImageJ cannot open interleaved "+bitDepth+"-bit images.");
							in.seek(saveLoc);
						}
						break;
				case SAMPLES_PER_PIXEL:
					fi.setSamplesPerPixel(value);
					if (value==3 && fi.getFileType()==FileInfo.GRAY8)
						fi.setFileType(FileInfo.RGB);
					else if (value==3 && fi.getFileType()==FileInfo.GRAY16_UNSIGNED)
						fi.setFileType(FileInfo.RGB48);
					else if (value==4 && fi.getFileType()==FileInfo.GRAY8)
						fi.setFileType(photoInterp==5?FileInfo.CMYK:FileInfo.ARGB);
					else if (value==4 && fi.getFileType()==FileInfo.GRAY16_UNSIGNED) {
						fi.setFileType(FileInfo.RGB48);
						if (photoInterp==5)  //assume cmyk
							fi.setWhiteIsZero(true);
					}
					break;
				case ROWS_PER_STRIP:
					fi.setRowsPerStrip(value);
					break;
				case X_RESOLUTION:
					double xScale = getRational(lvalue); 
					if (xScale!=0.0) fi.setPixelWidth(1.0/xScale); 
					break;
				case Y_RESOLUTION:
					double yScale = getRational(lvalue); 
					if (yScale!=0.0) fi.setPixelHeight(1.0/yScale); 
					break;
				case RESOLUTION_UNIT:
					if (value==1&&fi.getUnit()==null)
						fi.setUnit(" ");
					else if (value==2) {
						if (fi.getPixelWidth()==1.0/72.0) {
							fi.setPixelWidth(1.0);
							fi.setPixelHeight(1.0);
						} else
							fi.setUnit("inch");
					} else if (value==3)
						fi.setUnit("cm");
					break;
				case PLANAR_CONFIGURATION:  // 1=chunky, 2=planar
					if (value==2 && fi.getFileType()==FileInfo.RGB48)
							 fi.setFileType(FileInfo.RGB48_PLANAR);
					else if (value==2 && fi.getFileType()==FileInfo.RGB)
						fi.setFileType(FileInfo.RGB_PLANAR);
					else if (value!=2 && !(fi.getSamplesPerPixel()==1||fi.getSamplesPerPixel()==3||fi.getSamplesPerPixel()==4)) {
						String msg = "Unsupported SamplesPerPixel: " + fi.getSamplesPerPixel();
						error(msg);
					}
					break;
				case COMPRESSION:
					if (value==5)  {// LZW compression
						fi.setCompression(FileInfo.LZW);
						if (fi.getFileType()==FileInfo.GRAY12_UNSIGNED)
							error("ImageJ cannot open 12-bit LZW-compressed TIFFs");
					} else if (value==32773)  // PackBits compression
						fi.setCompression(FileInfo.PACK_BITS);
					else if (value==32946 || value==8) //8=Adobe deflate
						fi.setCompression(FileInfo.ZIP);
					else if (value!=1 && value!=0 && !(value==7&&fi.getWidth()<500)) {
						// don't abort with Spot camera compressed (7) thumbnails
						// otherwise, this is an unknown compression type
						fi.setCompression(FileInfo.COMPRESSION_UNKNOWN);
						error("ImageJ cannot open TIFF files " +
							"compressed in this fashion ("+value+")");
					}
					break;
				case SOFTWARE: case DATE_TIME: case HOST_COMPUTER: case ARTIST:
					if (ifdCount==1) {
						byte[] bytes = getString(count, lvalue);
						String s = bytes!=null?new String(bytes):null;
						saveMetadata(getName(tag), s);
					}
					break;
				case PREDICTOR:
					if (value==2 && fi.getCompression()==FileInfo.LZW)
						fi.setCompression(FileInfo.LZW_WITH_DIFFERENCING);
					if (value==3)
						IJ.log("TiffDecoder: unsupported predictor value of 3");
					break;
				case COLOR_MAP: 
					if (count==768)
						getColorMap(lvalue, fi);
					break;
				case TILE_WIDTH:
					error("ImageJ cannot open tiled TIFFs.\nTry using the Bio-Formats plugin.");
					break;
				case SAMPLE_FORMAT:
					if (fi.getFileType()==FileInfo.GRAY32_INT && value==FLOATING_POINT)
						fi.setFileType(FileInfo.GRAY32_FLOAT);
					if (fi.getFileType()==FileInfo.GRAY16_UNSIGNED) {
						if (value==SIGNED)
							fi.setFileType(FileInfo.GRAY16_SIGNED);
						if (value==FLOATING_POINT)
							error("ImageJ cannot open 16-bit float TIFFs");
					}
					break;
				case JPEG_TABLES:
					if (fi.getCompression()==FileInfo.JPEG)
						error("Cannot open JPEG-compressed TIFFs with separate tables");
					break;
				case IMAGE_DESCRIPTION: 
					if (ifdCount==1) {
						byte[] s = getString(count, lvalue);
						if (s!=null) saveImageDescription(s,fi);
					}
					break;
				case ORIENTATION:
					fi.setnImages(0); // file not created by ImageJ so look at all the IFDs
					break;
				case METAMORPH1: case METAMORPH2:
					if ((name.indexOf(".STK")>0||name.indexOf(".stk")>0) && fi.getCompression()==FileInfo.COMPRESSION_NONE) {
						if (tag==METAMORPH2)
							fi.setnImages(count);
						else
							fi.setnImages(9999);
					}
					break;
				case IPLAB: 
					fi.setnImages(value);
					break;
				case NIH_IMAGE_HDR: 
					if (count==256)
						decodeNIHImageHeader(value, fi);
					break;
 				case META_DATA_BYTE_COUNTS: 
					long saveLoc = in.getLongFilePointer();
					in.seek(lvalue);
					metaDataCounts = new int[count];
					for (int c=0; c<count; c++)
						metaDataCounts[c] = getInt();
					in.seek(saveLoc);
					break;
 				case META_DATA: 
 					getMetaData(value, fi);
 					break;
				default:
					if (tag>10000 && tag<32768 && ifdCount>1)
						return null;
			}
		}
		fi.setFileFormat(fi.TIFF);
		fi.setFileName(name);
		fi.setDirectory(directory);
		if (url!=null)
			fi.setUrl(url);
		return fi;
	}

	void getMetaData(int loc, FileInfo fi) throws IOException {
		if (metaDataCounts==null || metaDataCounts.length==0)
			return;
		int maxTypes = 10;
		long saveLoc = in.getLongFilePointer();
		in.seek(loc);
		int n = metaDataCounts.length;
		int hdrSize = metaDataCounts[0];
		if (hdrSize<12 || hdrSize>804) {
			in.seek(saveLoc);
			return;
		}
		int magicNumber = getInt();
		if (magicNumber!=MAGIC_NUMBER)  { // "IJIJ"
			in.seek(saveLoc);
			return;
		}
		int nTypes = (hdrSize-4)/8;
		int[] types = new int[nTypes];
		int[] counts = new int[nTypes];		
		if (debugMode) {
			dInfo += "Metadata:\n";
			dInfo += "   Entries: "+(metaDataCounts.length-1)+"\n";
			dInfo += "   Types: "+nTypes+"\n";
		}
		int extraMetaDataEntries = 0;
		int index = 1;
		for (int i=0; i<nTypes; i++) {
			types[i] = getInt();
			counts[i] = getInt();
			if (types[i]<0xffffff)
				extraMetaDataEntries += counts[i];
			if (debugMode) {
				String id = "unknown";
				if (types[i]==INFO) id = "Info property";
				if (types[i]==LABELS) id = "slice labels";
				if (types[i]==RANGES) id = "display ranges";
				if (types[i]==LUTS) id = "luts";
				if (types[i]==PLOT) id = "plot";
				if (types[i]==ROI) id = "roi";
				if (types[i]==OVERLAY) id = "overlay";
				if (types[i]==PROPERTIES) id = "properties";
				int len = metaDataCounts[index];
				int count = counts[i];
				index += count;
				if (index>=metaDataCounts.length) index=1;
				String lenstr = count==1?", length=":", length[0]=";
				dInfo += "   "+i+", type="+id+", count="+count+lenstr+len+"\n";
			}
		}
		fi.setMetaDataTypes(new int[extraMetaDataEntries]);
		fi.setMetaData(new byte[extraMetaDataEntries][]);
		int start = 1;
		int eMDindex = 0;
		for (int i=0; i<nTypes; i++) {
			if (types[i]==INFO)
				getInfoProperty(start, fi);
			else if (types[i]==LABELS)
				getSliceLabels(start, start+counts[i]-1, fi);
			else if (types[i]==RANGES)
				getDisplayRanges(start, fi);
			else if (types[i]==LUTS)
				getLuts(start, start+counts[i]-1, fi);
			else if (types[i]==PLOT)
				getPlot(start, fi);
			else if (types[i]==ROI)
				getRoi(start, fi);
			else if (types[i]==OVERLAY)
				getOverlay(start, start+counts[i]-1, fi);
			else if (types[i]==PROPERTIES)
				getProperties(start, start+counts[i]-1, fi);
			else if (types[i]<0xffffff) {
				for (int j=start; j<start+counts[i]; j++) { 
					int len = metaDataCounts[j]; 
					fi.getMetaData()[eMDindex] = new byte[len]; 
					in.readFully(fi.getMetaData()[eMDindex], len); 
					fi.getMetaDataTypes()[eMDindex] = types[i]; 
					eMDindex++; 
				} 
			} else
				skipUnknownType(start, start+counts[i]-1);
			start += counts[i];
		}
		in.seek(saveLoc);
	}

	void getInfoProperty(int first, FileInfo fi) throws IOException {
		int len = metaDataCounts[first];
	    byte[] buffer = new byte[len];
		in.readFully(buffer, len);
		len /= 2;
		char[] chars = new char[len];
		if (littleEndian) {
			for (int j=0, k=0; j<len; j++)
				chars[j] = (char)(buffer[k++]&255 + ((buffer[k++]&255)<<8));
		} else {
			for (int j=0, k=0; j<len; j++)
				chars[j] = (char)(((buffer[k++]&255)<<8) + (buffer[k++]&255));
		}
		fi.setInfo(new String(chars));
	}

	void getSliceLabels(int first, int last, FileInfo fi) throws IOException {
		fi.setSliceLabels(new String[last-first+1]);
	    int index = 0;
	    byte[] buffer = new byte[metaDataCounts[first]];
		for (int i=first; i<=last; i++) {
			int len = metaDataCounts[i];
			if (len>0) {
				if (len>buffer.length)
					buffer = new byte[len];
				in.readFully(buffer, len);
				len /= 2;
				char[] chars = new char[len];
				if (littleEndian) {
					for (int j=0, k=0; j<len; j++)
						chars[j] = (char)(buffer[k++]&255 + ((buffer[k++]&255)<<8));
				} else {
					for (int j=0, k=0; j<len; j++)
						chars[j] = (char)(((buffer[k++]&255)<<8) + (buffer[k++]&255));
				}
				fi.getSliceLabels()[index++] = new String(chars);
				//ij.IJ.log(i+"  "+fi.sliceLabels[i-1]+"  "+len);
			} else
				fi.getSliceLabels()[index++] = null;
		}
	}

	void getDisplayRanges(int first, FileInfo fi) throws IOException {
		int n = metaDataCounts[first]/8;
		fi.setDisplayRanges(new double[n]);
		for (int i=0; i<n; i++)
			fi.getDisplayRanges()[i] = readDouble();
	}

	void getLuts(int first, int last, FileInfo fi) throws IOException {
		fi.setChannelLuts(new byte[last-first+1][]);
	    int index = 0;
		for (int i=first; i<=last; i++) {
			int len = metaDataCounts[i];
			fi.getChannelLuts()[index] = new byte[len];
            in.readFully(fi.getChannelLuts()[index], len);
            index++;
		}
	}

	void getRoi(int first, FileInfo fi) throws IOException {
		int len = metaDataCounts[first];
		fi.setRoi(new byte[len]); 
		in.readFully(fi.getRoi(), len); 
	}

	void getPlot(int first, FileInfo fi) throws IOException {
		int len = metaDataCounts[first];
		fi.setPlot(new byte[len]);
		in.readFully(fi.getPlot(), len);
	}

	void getOverlay(int first, int last, FileInfo fi) throws IOException {
		fi.setOverlay(new byte[last-first+1][]);
	    int index = 0;
		for (int i=first; i<=last; i++) {
			int len = metaDataCounts[i];
			fi.getOverlay()[index] = new byte[len];
            in.readFully(fi.getOverlay()[index], len);
            index++;
		}
	}

	void getProperties(int first, int last, FileInfo fi) throws IOException {
		fi.setProperties(new String[last-first+1]);
	    int index = 0;
	    byte[] buffer = new byte[metaDataCounts[first]];
		for (int i=first; i<=last; i++) {
			int len = metaDataCounts[i];
			if (len>buffer.length)
				buffer = new byte[len];
			in.readFully(buffer, len);
			len /= 2;
			char[] chars = new char[len];
			if (littleEndian) {
				for (int j=0, k=0; j<len; j++)
					chars[j] = (char)(buffer[k++]&255 + ((buffer[k++]&255)<<8));
			} else {
				for (int j=0, k=0; j<len; j++)
					chars[j] = (char)(((buffer[k++]&255)<<8) + (buffer[k++]&255));
			}
			fi.getProperties()[index++] = new String(chars);
		}
	}

	void error(String message) throws IOException {
		if (in!=null) in.close();
		throw new IOException(message);
	}
	
	void skipUnknownType(int first, int last) throws IOException {
	    byte[] buffer = new byte[metaDataCounts[first]];
		for (int i=first; i<=last; i++) {
			int len = metaDataCounts[i];
            if (len>buffer.length)
                buffer = new byte[len];
            in.readFully(buffer, len);
		}
	}

	public void enableDebugging() {
		debugMode = true;
	}
		
	public FileInfo[] getTiffInfo() throws IOException {
		long ifdOffset;
		ArrayList list = new ArrayList();
		if (in==null)
			in = new RandomAccessStream(new RandomAccessFile(new File(directory+name), "r"));
		ifdOffset = OpenImageFileHeader();
		if (ifdOffset<0L) {
			in.close();
			return null;
		}
		if (debugMode) dInfo = "\n  " + name + ": opening\n";
		while (ifdOffset>0L) {
			in.seek(ifdOffset);
			FileInfo fi = OpenIFD();
			if (fi!=null) {
				list.add(fi);
				ifdOffset = ((long)getInt())&0xffffffffL;
			} else
				ifdOffset = 0L;
			if (debugMode && ifdCount<10) dInfo += "nextIFD=" + ifdOffset + "\n";
			if (fi!=null && fi.getnImages()>1)
				ifdOffset = 0L;   // ignore extra IFDs in ImageJ and NIH Image stacks
		}
		if (list.size()==0) {
			in.close();
			return null;
		} else {
			FileInfo[] info = (FileInfo[])list.toArray(new FileInfo[list.size()]);
			if (debugMode) info[0].setDebugInfo(dInfo);
			if (url!=null) {
				in.seek(0);
				info[0].setInputStream(in);
			} else
				in.close();
			if (info[0].getInfo()==null)
				info[0].setInfo(tiffMetadata);
			FileInfo fi = info[0];
			if (fi.getFileType()==FileInfo.GRAY16_UNSIGNED && fi.getDescription()==null)
				fi.setLutSize(0); // ignore troublesome non-ImageJ 16-bit LUTs
			if (debugMode) {
				int n = info.length;
				fi.setDebugInfo("number of IFDs: "+ n + "\n"
				+"offset to first image: "+fi.getOffset()+ "\n"
				+"gap between images: "+getGapInfo(info) + "\n"
				+"little-endian byte order: "+fi.isIntelByteOrder() + "\n");
			}
			return info;
		}
	}
	
	String getGapInfo(FileInfo[] fi) {
		if (fi.length<2) return "0";
		long minGap = Long.MAX_VALUE;
		long maxGap = -Long.MAX_VALUE;
		for (int i=1; i<fi.length; i++) {
			long gap = fi[i].getOffset()-fi[i-1].getOffset();
			if (gap<minGap) minGap = gap;
			if (gap>maxGap) maxGap = gap;
		}
		long imageSize = fi[0].getWidth()*fi[0].getHeight()*fi[0].getBytesPerPixel();
		minGap -= imageSize;
		maxGap -= imageSize;
		if (minGap==maxGap)
			return ""+minGap;
		else 
			return "varies ("+minGap+" to "+maxGap+")";
	}

}
