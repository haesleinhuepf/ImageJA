import ij.*;
import ij.plugin.*;
import ij.gui.*;
import ij.io.*;
import ij.process.*;
import java.awt.*;
import java.io.*;

import quicktime.*;
import quicktime.io.*;
import quicktime.qd.*;
import quicktime.std.clocks.*;
import quicktime.std.image.*;
import quicktime.std.movies.*;
import quicktime.std.movies.media.*;
import quicktime.std.movies.media.VideoMediaHandler;
import quicktime.app.view.*; //this is where several things were moved under QTJ 6.1!!
import quicktime.std.image.GraphicsImporter;
import quicktime.std.image.GraphicsMode;
import quicktime.app.display.*;
import quicktime.app.view.MoviePlayer; 
import quicktime.util.*;
import quicktime.std.StdQTConstants;
/**
	Opens a quicktime movie as an RGB stack, an 8-bit grayscale stack, or as a virtual stack.
	Requires QuickTime for Java, part of QuickTime (requires custom install on Windows).

	Changes made to allow compatibility with Java VM 1.4.1 and QTJ 6.1 under Mac OS X 10.3
	by Jeff Hardin, Dept. of Zoololgy, Univ. of Wisconsin, jdhardin@wisc.edu, 11/22/03
	
	Virtual stack support contributed by Jeffrey Woodward on 2008/11/26.
*/
public class QT_Movie_Opener implements PlugIn, QDConstants, StdQTConstants, MovieDrawingComplete {
	
	QTImageProducer qtip;// DEPRECATED!!
	QTFile qtf = null;
	Image javaImage = null;
	MoviePlayer moviePlayer;
	ImageStack stack;
	int i, numFrames, totalFrames, nextTime;
	Image img;
	static boolean grayscale;
	static boolean virtualStack;
	
	public void run(String arg) {
		if (IJ.is64Bit() && IJ.isMacintosh()) {
			IJ.error("This plugin requires a 32-bit version of Java");
			return;
		}
		try {
			Class qts = Class.forName("quicktime.QTSession");
		} catch (Exception e) {
			IJ.error("Requires QuickTime for Java, available as a\n"
						+"custom install with QuickTime 4.0 or later.");
			return;
		}
		OpenDialog od = new OpenDialog("Open QuickTime...", arg);
		String directory = od.getDirectory();
		String name = od.getFileName();
		if (name==null) return;
		
		GenericDialog gd = new GenericDialog("QT Movie Opener");
		gd.addCheckbox("Convert to 8-bit grayscale", grayscale);
		gd.setInsets(0,30,10);
		gd.addMessage("Reduces memory required by factor of 4");
		gd.addCheckbox("Use Virtual Stack", virtualStack);
		gd.showDialog();
		if (gd.wasCanceled())
			return;
		grayscale = gd.getNextBoolean();
		virtualStack = gd.getNextBoolean();
		String path = directory+name;

		if (virtualStack) {
			openAsVirtualStack(path, grayscale);
			return;
		}

		try {
			QTSession.open();			
			qtf = new QTFile(path);
			IJ.showStatus("Opening \""+name+"\"");
			if (IJ.debugMode) IJ.write("OpenMovieFile.asRead(qtf)");
			OpenMovieFile openMovieFile = OpenMovieFile.asRead(qtf);
			IJ.showProgress(0.0);
			if (IJ.debugMode) IJ.write("Movie.fromFile");
			Movie m = Movie.fromFile (openMovieFile);
			if (IJ.debugMode) IJ.write("m.getTrackCount()");
			int numTracks = m.getTrackCount();
			if (IJ.debugMode) IJ.write("numTracks: "+numTracks);
			int trackMostLikely = 0;
			int trackNum = 0;
			while((++trackNum <= numTracks) && (trackMostLikely==0)) {
				Track imageTrack = m.getTrack(trackNum);
				QDDimension d = imageTrack.getSize();
				if (d.getWidth() > 0) trackMostLikely = trackNum; //first track with width != soundtrack
			}
			if (IJ.debugMode) IJ.write("m.getTrack: "+trackMostLikely);
			Track imageTrack = m.getTrack(trackMostLikely);
			QDDimension d = imageTrack.getSize();
			int width = d.getWidth();
			int height = d.getHeight();
			
			moviePlayer = new MoviePlayer (m); 			
			qtip = new QTImageProducer (moviePlayer, new Dimension(width,height));
			img = Toolkit.getDefaultToolkit().createImage(qtip);
			boolean needsRedrawing = qtip.isRedrawing();
			if (IJ.debugMode) IJ.write("needsRedrawing: "+needsRedrawing);
			int maxTime = m.getDuration();
			//m.setDrawingCompleteProc(movieDrawingCallWhenChanged, this);
                        
			if (IJ.debugMode) IJ.write("Counting frames");
                      
  			TimeInfo timeInfo = new TimeInfo(0, 0);
                        moviePlayer.setTime(0);
                        totalFrames = 0;
                        do {
                            totalFrames++;
                            timeInfo = imageTrack.getNextInterestingTime(nextTimeMediaSample, timeInfo.time, 1f);
                        } while (timeInfo.time > -1);
                       
			int size = (width*height*totalFrames*4)/(1024*1024);
			IJ.showStatus("Allocating "+width+"x"+height+"x"+totalFrames+" stack ("+size+"MB)");
			stack = allocateStack(width, height, totalFrames);
			if (stack==null) {
				QTSession.close();
				if (stack==null)
					IJ.outOfMemory("Movie_Opener");
				return;
			}
			numFrames = totalFrames;
			if (stack.getSize()<numFrames)
				numFrames = stack.getSize();
                        
 			if (IJ.debugMode) IJ.write("Rewinding and reading movie");
                          
                        moviePlayer.setTime(0);
                        i = 0;
  			            nextTime = 0;
                         do {
                           i++;   
                            if (needsRedrawing)
					             qtip.redraw(null);
                            qtip.updateConsumers (null);
                            ImageProcessor ip = new ColorProcessor(img);
                            if (grayscale)
                                    ip = ip.convertToByte(false);
                            stack.setPixels(ip.getPixels(), i);
                            IJ.showStatus((i) + "/" + numFrames);
                            IJ.showProgress((double)(i)/totalFrames);
                            timeInfo = imageTrack.getNextInterestingTime(nextTimeMediaSample, nextTime, 1f);
                            nextTime = timeInfo.time;
                            moviePlayer.setTime(nextTime);
                        } while (nextTime > -1);
			openMovieFile.close();
	 		QTSession.close();
	 	}
		catch(Exception e) {
			QTSession.close();
			IJ.showProgress(1.0);
			String msg = e.getMessage();
			if (msg==null) msg = ""+e;
			if (msg.equals("-108") && IJ.isMacintosh())
				msg += "\n \nTry allocating more memory \nto the ImageJ application.";
			if (!msg.equals("-128"))
				IJ.error("Open movie failed: "+ msg);
			return;
		}
		catch (NoClassDefFoundError e) {
			IJ.error("QuickTime for Java required");
			return;
		}
		
		new ImagePlus(name, stack).show();
	}

	ImageStack allocateStack(int width, int height, int size) {
		ImageStack stack=null;
		byte[] temp;
		try {
			stack = new ImageStack(width, height);
			int mem = 0;
			for (int i=0; i<size; i++) {
				if (grayscale)
					stack.addSlice(null, new byte[width*height]);
				else
					stack.addSlice(null, new int[width*height]);
				mem += width*height*4;
				//IJ.write((i+1)+"/"+size+" "+mem/1024);
			}
			temp = new byte[width*height*4*5+1000000];
	 	}
		catch(OutOfMemoryError e) {
			if (stack!=null) {
				Object[] arrays = stack.getImageArray();
				if (arrays!=null)
					for (int i=0; i<arrays.length; i++)
				arrays[i] = null;
			}
			stack = null;
		}
		temp = null;
		System.gc();
		System.gc();
		return stack;
	}

	public int execute (Movie m) {
		try {
			qtip.updateConsumers (null);
		} catch (QTException e) {
			return e.errorCode();
		}
		return 0;
	}
	
	void openAsVirtualStack(String path, boolean eightBit) {
		try {
			QTFile qtf = new QTFile(path);
			if (qtf==null) return;
			if (!QTSession.isInitialized())
				QTSession.open();
			VirtualStack stack = new QTVirtualStack(qtf, eightBit);
			ImagePlus imp = new ImagePlus(qtf.getName(), stack);
			imp.show();
		} catch(QTException qte) {
			IJ.error(qte.getMessage());
		}
	}

}

