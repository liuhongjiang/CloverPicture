package com.fedorvlasov.lazylist;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.widget.ImageView;

public class ImageLoader {
	
	public interface OnImageLoadFinishedListener {
		public void onFinished();
	}
    
	private int scaleSize;
    MemoryCache memoryCache=new MemoryCache();
    FileCache fileCache;
    private Map<ImageView, String> imageViews=Collections.synchronizedMap(new WeakHashMap<ImageView, String>());
    ExecutorService executorService;
    Handler handler=new Handler();//handler to display images in UI thread
    
    public ImageLoader(Context context) {
    	// 70 was the default scaleSize.
    	this(context, 70);
    }
    
    /**
     * Suggested scale size is the maximum size you may want to display the image.
     * For example if you have a list of images 4 images wide, you would want
     * scale size to be 1/4 the screen width - this ensures all images are less
     * than this width, and not wasting space. Some may be scaled smaller than this,
     * up to a minimum of half this size.
     * @param context
     * @param scaleSize
     */
    public ImageLoader(Context context, int scaleSize){
    	this.scaleSize = scaleSize;
        fileCache=new FileCache(context);
        executorService=Executors.newFixedThreadPool(5);
    }
    
    final int stub_id=R.drawable.stub;
    public void DisplayImage(String url, ImageView imageView, boolean shouldScale, OnImageLoadFinishedListener listener)
    {
        imageViews.put(imageView, url);
        Bitmap bitmap=memoryCache.get(url);
        if(bitmap!=null) {
            imageView.setImageBitmap(bitmap);
            if(listener != null) listener.onFinished();
        } else
        {
            queuePhoto(url, imageView, shouldScale, listener);
            imageView.setImageResource(stub_id);
        }
    }
        
    private void queuePhoto(String url, ImageView imageView, boolean shouldScale, OnImageLoadFinishedListener listener)
    {
        PhotoToLoad p=new PhotoToLoad(url, imageView, shouldScale);
        executorService.submit(new PhotosLoader(p, listener));
    }
    
    private Bitmap getBitmap(String url, boolean shouldScale) 
    {
        File f=fileCache.getFile(url);
        
        //from SD cache
        Bitmap b = decodeFile(f, shouldScale);
        if(b!=null)
            return b;
        
        //from web
        try {
            Bitmap bitmap=null;
            URL imageUrl = new URL(url);
            HttpURLConnection conn = (HttpURLConnection)imageUrl.openConnection();
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(true);
            InputStream is=conn.getInputStream();
            OutputStream os = new FileOutputStream(f);
            Utils.CopyStream(is, os);
            os.close();
            bitmap = decodeFile(f, shouldScale);
            return bitmap;
        } catch (Throwable ex){
           ex.printStackTrace();
           if(ex instanceof OutOfMemoryError)
               memoryCache.clear();
           return null;
        }
    }

    //decodes image and scales it to reduce memory consumption
    private Bitmap decodeFile(File f, boolean shouldScale){
        try {
        	if(shouldScale){
	            //decode image size
	            BitmapFactory.Options o = new BitmapFactory.Options();
	            o.inJustDecodeBounds = true;
	            FileInputStream stream1=new FileInputStream(f);
	            BitmapFactory.decodeStream(stream1,null,o);
	            stream1.close();
	            
	            //Find the correct scale value. It should be the power of 2.
	            final int REQUIRED_SIZE=scaleSize;
	            int width_tmp=o.outWidth, height_tmp=o.outHeight;
	            int scale=1;
	            while(true){
	                if(width_tmp/2<REQUIRED_SIZE || height_tmp/2<REQUIRED_SIZE)
	                    break;
	                width_tmp/=2;
	                height_tmp/=2;
	                scale*=2;
	            }
	            
	            //decode with inSampleSize
	            BitmapFactory.Options o2 = new BitmapFactory.Options();
	            o2.inSampleSize=scale;
	            FileInputStream stream2=new FileInputStream(f);
	            Bitmap bitmap=BitmapFactory.decodeStream(stream2, null, o2);
	            stream2.close();
	            return bitmap;
        	} else {
        		return BitmapFactory.decodeStream(new FileInputStream(f));
        	}
        } catch (FileNotFoundException e) {
        } 
        catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }
    
    //Task for the queue
    private class PhotoToLoad
    {
        public String url;
        public ImageView imageView;
        public boolean shouldScale;
        public PhotoToLoad(String u, ImageView i, boolean s){
            url=u; 
            imageView=i;
            shouldScale = s;
        }
    }
    
    class PhotosLoader implements Runnable {
        PhotoToLoad photoToLoad;
        OnImageLoadFinishedListener listener;
        PhotosLoader(PhotoToLoad photoToLoad, OnImageLoadFinishedListener listener){
            this.photoToLoad=photoToLoad;
            this.listener = listener;
        }
        
        @Override
        public void run() {
            try{
                if(imageViewReused(photoToLoad))
                    return;
                Bitmap bmp=getBitmap(photoToLoad.url, photoToLoad.shouldScale);
                memoryCache.put(photoToLoad.url, bmp);
                if(imageViewReused(photoToLoad))
                    return;
                BitmapDisplayer bd=new BitmapDisplayer(bmp, photoToLoad, listener);
                handler.post(bd);
            }catch(Throwable th){
                th.printStackTrace();
            }
        }
    }
    
    boolean imageViewReused(PhotoToLoad photoToLoad){
        String tag=imageViews.get(photoToLoad.imageView);
        if(tag==null || !tag.equals(photoToLoad.url))
            return true;
        return false;
    }
    
    //Used to display bitmap in the UI thread
    class BitmapDisplayer implements Runnable
    {
        Bitmap bitmap;
        PhotoToLoad photoToLoad;
        OnImageLoadFinishedListener listener;
        public BitmapDisplayer(Bitmap b, PhotoToLoad p, OnImageLoadFinishedListener l){
        	bitmap=b;
        	photoToLoad=p;
        	listener=l;
    	}
        public void run()
        {
            if(imageViewReused(photoToLoad))
                return;
            if(bitmap!=null)
                photoToLoad.imageView.setImageBitmap(bitmap);
            else
                photoToLoad.imageView.setImageResource(stub_id);
            if(listener!=null)listener.onFinished();
        }
    }

    public void clearCache() {
        memoryCache.clear();
        fileCache.clear();
    }

}
