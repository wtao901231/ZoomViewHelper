/*******************************************************************************
 * Copyright 2011, 2012 Chris Banes.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package minus.android.support.view;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Matrix.ScaleToFit;
import android.graphics.PointF;
import android.graphics.RectF;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnLongClickListener;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.Interpolator;
import android.widget.ImageView;
import android.widget.OverScroller;

import java.lang.ref.WeakReference;

import minus.android.support.view.gestures.OnGestureListener;
import minus.android.support.view.gestures.VersionedGestureDetector;

import static android.view.MotionEvent.ACTION_CANCEL;
import static android.view.MotionEvent.ACTION_DOWN;
import static android.view.MotionEvent.ACTION_UP;

public class ZoomViewHelper implements View.OnTouchListener,
        OnGestureListener,
        ViewTreeObserver.OnGlobalLayoutListener {

    public interface IZoomView {
        boolean hasDrawable();
        ScaleType getScaleType();
        void setScaleType(ScaleType scaleType);
        void setImageMatrix(Matrix m);
        void getDisplayRect(RectF outRect);
        int getIntrinsicWidth();
        int getIntrinsicHeight();
    }

    /**
     * Options for scaling the bounds of an image to the bounds of this view.
     */
    public enum ScaleType {
        /**
         * Scale using the image matrix when drawing. The image matrix can be set using
         * {@link ImageView#setImageMatrix(Matrix)}. From XML, use this syntax:
         * <code>android:scaleType="matrix"</code>.
         */
        MATRIX      (0),
        /**
         * Scale the image using {@link ScaleToFit#FILL}.
         * From XML, use this syntax: <code>android:scaleType="fitXY"</code>.
         */
        FIT_XY      (1),
        /**
         * Scale the image using {@link ScaleToFit#START}.
         * From XML, use this syntax: <code>android:scaleType="fitStart"</code>.
         */
        FIT_START   (2),
        /**
         * Scale the image using {@link ScaleToFit#CENTER}.
         * From XML, use this syntax:
         * <code>android:scaleType="fitCenter"</code>.
         */
        FIT_CENTER  (3),
        /**
         * Scale the image using {@link ScaleToFit#END}.
         * From XML, use this syntax: <code>android:scaleType="fitEnd"</code>.
         */
        FIT_END     (4),
        /**
         * Center the image in the view, but perform no scaling.
         * From XML, use this syntax: <code>android:scaleType="center"</code>.
         */
        CENTER      (5),
        /**
         * Scale the image uniformly (maintain the image's aspect ratio) so
         * that both dimensions (width and height) of the image will be equal
         * to or larger than the corresponding dimension of the view
         * (minus padding). The image is then centered in the view.
         * From XML, use this syntax: <code>android:scaleType="centerCrop"</code>.
         */
        CENTER_CROP (6),
        /**
         * Scale the image uniformly (maintain the image's aspect ratio) so
         * that both dimensions (width and height) of the image will be equal
         * to or less than the corresponding dimension of the view
         * (minus padding). The image is then centered in the view.
         * From XML, use this syntax: <code>android:scaleType="centerInside"</code>.
         */
        CENTER_INSIDE (7);

        ScaleType(int ni) {
            nativeInt = ni;
        }
        final int nativeInt;
    }
	
	public static final float DEFAULT_OVER_MAX_SCALE = 6f;
    public static final float DEFAULT_MAX_SCALE = 3.0f;
    public static final float DEFAULT_MID_SCALE = 2.0f;
    public static final float DEFAULT_MIN_SCALE = 1.0f;
    public static final int DEFAULT_ZOOM_DURATION = 200;

    private static final String LOG_TAG = "ZoomViewHelper";
    private static final boolean DEBUG = false;

    static final Interpolator sInterpolator = new AccelerateDecelerateInterpolator();
    int ZOOM_DURATION = DEFAULT_ZOOM_DURATION;

    static final int EDGE_NONE = -1;
    static final int EDGE_LEFT = 0;
    static final int EDGE_RIGHT = 1;
    static final int EDGE_BOTH = 2;

    private float mMinScale = DEFAULT_MIN_SCALE;
    private float mMidScale = DEFAULT_MID_SCALE;
    private float mMaxScale = DEFAULT_MAX_SCALE;
    private float mOverMaxScale = DEFAULT_OVER_MAX_SCALE;
    private float mOverMinScale = 0;

    private boolean mAllowParentInterceptOnEdge = true;
    private boolean mBlockParentIntercept = false;

    private static void checkZoomLevels(float minZoom, float midZoom,
                                        float maxZoom) {
        if (minZoom >= midZoom) {
            throw new IllegalArgumentException(
                    "MinZoom has to be less than MidZoom");
        } else if (midZoom >= maxZoom) {
            throw new IllegalArgumentException(
                    "MidZoom has to be less than MaxZoom");
        }
    }

    /**
     * @return true if the View exists, and it's Drawable existss
     */
    private static boolean hasDrawable(IZoomView zoomView) {
        return null != zoomView && zoomView.hasDrawable();
    }

    /**
     * @return true if the ScaleType is supported.
     */
    private static boolean isSupportedScaleType(final ScaleType scaleType) {
        if (null == scaleType) {
            return false;
        }

        switch (scaleType) {
            case MATRIX:
                throw new IllegalArgumentException(scaleType.name()
                        + " is not supported in PhotoView");

            default:
                return true;
        }
    }

    /**
     * Set's the View's ScaleType to Matrix.
     */
    private static void setViewScaleTypeMatrix(IZoomView zoomView) {
        /**
         * PhotoView sets it's own ScaleType to Matrix, then diverts all calls
         * setScaleType to this.setScaleType automatically.
         */
		if (null != zoomView && !ScaleType.MATRIX.equals(zoomView.getScaleType())) {
			zoomView.setScaleType(ScaleType.MATRIX);
		}
    }

    private WeakReference<View> mView;
    private WeakReference<IZoomView> mZoomInterface;

    // Gesture Detectors
    private GestureDetector mGestureDetector;
    private minus.android.support.view.gestures.GestureDetector mScaleDragDetector;

    // These are set so we don't keep allocating them on the heap
    private final Matrix mBaseMatrix = new Matrix();
    private final Matrix mDrawMatrix = new Matrix();
    private final Matrix mTempSuppMatrix = new Matrix();
    private final Matrix mSuppMatrix = new Matrix();
    private final RectF mDisplayRect = new RectF();
    private final float[] mMatrixValues = new float[9];
    
    private PointF mLastFocusCenter = new PointF();

    // Listeners
    private OnMatrixChangedListener mMatrixChangeListener;
    private OnPhotoTouchEvent mPhotoTouchListener;
    private OnPhotoTapListener mPhotoTapListener;
    private OnViewTapListener mViewTapListener;
    private OnLongClickListener mLongClickListener;
    private OnScaleChangeListener mScaleChangeListener;

    private int mIvTop, mIvRight, mIvBottom, mIvLeft;
    private FlingRunnable mCurrentFlingRunnable;
    private int mScrollEdge = EDGE_BOTH;

    private boolean mAutoZoomBackEnabled = true;
    private boolean mZoomEnabled;
    private ScaleType mScaleType = ScaleType.FIT_CENTER;

    public ZoomViewHelper(View zoomView, IZoomView zoomInterface) {
        this(zoomView, zoomInterface, true);
    }

    public ZoomViewHelper(View zoomView, IZoomView zoomInterface, boolean zoomable) {
        mView = new WeakReference<View>(zoomView);
        mZoomInterface = new WeakReference<IZoomView>(zoomInterface);

        zoomView.setDrawingCacheEnabled(true);
        zoomView.setOnTouchListener(this);

        ViewTreeObserver observer = zoomView.getViewTreeObserver();
        if (null != observer)
            observer.addOnGlobalLayoutListener(this);

        // Make sure we using MATRIX Scale Type
        setViewScaleTypeMatrix(zoomInterface);

        if (zoomView.isInEditMode()) {
            return;
        }
        // Create Gesture Detectors...
        mScaleDragDetector = VersionedGestureDetector.newInstance(
                zoomView.getContext(), this);

        mGestureDetector = new GestureDetector(zoomView.getContext(),
                new GestureDetector.SimpleOnGestureListener() {

                    // forward long click listener
                    @Override
                    public void onLongPress(MotionEvent e) {
                        if (null != mLongClickListener) {
                            mLongClickListener.onLongClick(getView());
                        }
                    }
                });

        mGestureDetector.setOnDoubleTapListener(new DefaultOnDoubleTapListener(this));

        // Finally, update the UI so that we're zoomable
        setZoomable(zoomable);
    }

    public void setOnDoubleTapListener(GestureDetector.OnDoubleTapListener newOnDoubleTapListener) {
        if (newOnDoubleTapListener != null) {
            this.mGestureDetector.setOnDoubleTapListener(newOnDoubleTapListener);
        } else {
            this.mGestureDetector.setOnDoubleTapListener(new DefaultOnDoubleTapListener(this));
        }
    }

    public void setOnScaleChangeListener(OnScaleChangeListener onScaleChangeListener) {
        this.mScaleChangeListener = onScaleChangeListener;
    }

    public boolean canZoom() {
        return mZoomEnabled;
    }

    /**
     * Clean-up the resources attached to this object. This needs to be called when the View is
     * no longer used. A good example is from {@link View#onDetachedFromWindow()} or
     * from {@link android.app.Activity#onDestroy()}.
     */
    @SuppressWarnings("deprecation")
    public void cleanup() {
        if (null == mView) {
            return; // cleanup already done
        }

        final View zoomView = mView.get();

        if (null != zoomView) {
            // Remove this as a global layout listener
            ViewTreeObserver observer = zoomView.getViewTreeObserver();
            if (null != observer && observer.isAlive()) {
                observer.removeGlobalOnLayoutListener(this);
            }

            // Remove the View's reference to this
            zoomView.setOnTouchListener(null);

            // make sure a pending fling runnable won't be run
            cancelFling();
        }

        if (null != mGestureDetector) {
            mGestureDetector.setOnDoubleTapListener(null);
        }

        // Clear listeners too
        mMatrixChangeListener = null;
        mPhotoTapListener = null;
        mViewTapListener = null;

        // Finally, clear View
        mView = null;
    }

    public RectF getDisplayRect() {
        checkMatrixBounds();
        return getDisplayRect(getDrawMatrix());
    }

    public boolean setDisplayMatrix(Matrix finalMatrix) {
        if (finalMatrix == null)
            throw new IllegalArgumentException("Matrix cannot be null");

        IZoomView zoomView = asInterface();
        if (null == zoomView)
            return false;

        if (!zoomView.hasDrawable())
            return false;

        mSuppMatrix.set(finalMatrix);
        setViewMatrix(getDrawMatrix());
        checkMatrixBounds();

        return true;
    }

    public void setRotationTo(float degrees) {
        mSuppMatrix.setRotate(degrees % 360);
        checkAndDisplayMatrix();
    }

    public void setRotationBy(float degrees) {
        mSuppMatrix.postRotate(degrees % 360);
        checkAndDisplayMatrix();
    }

    public IZoomView asInterface() {
        if (null != mZoomInterface) {
            return mZoomInterface.get();
        }
        return null;
    }

    public View getView() {
        View zoomView = null;

        if (null != mView) {
            zoomView = mView.get();
        }

        // If we don't have an View, call cleanup()
        if (null == zoomView) {
            cleanup();
            Log.i(LOG_TAG,
                    "View no longer exists. You should not use this PhotoViewAttacher any more.");
        }

        return zoomView;
    }

    public float getMinimumScale() {
        return mMinScale;
    }

    public float getMediumScale() {
        return mMidScale;
    }
    
    public float getMaximumScale() {
        return mMaxScale;
    }

    public float getScale() {
        return getScale(mSuppMatrix);
    }

    float getScale(Matrix m) {
       return  (float) Math.sqrt((float) Math.pow(getValue(m, Matrix.MSCALE_X), 2) + (float) Math.pow(getValue(m, Matrix.MSKEW_Y), 2));
    }

    public ScaleType getScaleType() {
        return mScaleType;
    }

    public static final int COORDS_L2R = 1<<1;
    public static final int COORDS_R2L = 1<<2;
    public static final int COORDS_T2B = 1<<3;
    public static final int COORDS_B2T = 1<<4;

    public static final int COORDS_VIEW = COORDS_L2R | COORDS_T2B;
    public static final int COORDS_GL = COORDS_L2R | COORDS_B2T;

    private int mCoordsFlag = COORDS_VIEW;
    public void setCoordsType(int type) {
        mCoordsFlag = type;
    }
    public void setCoordsFlag(int x, int y) {
        mCoordsFlag = x | y;
    }
    private int getCoordsOrientationX() {
        if (0 != (mCoordsFlag | COORDS_R2L)) {
            return -1;
        } else {
            return 1;
        }
    }
    private int getCoordsOrientationY() {
        if (0 != (mCoordsFlag | COORDS_B2T)) {
            return -1;
        } else {
            return 1;
        }
    }

    @Override
    public void onDrag(float dx, float dy) {
        if (mScaleDragDetector.isScaling()) {
            return; // Do not drag if we are already scaling
        }

        if (DEBUG) {
            Log.d(LOG_TAG,
                    String.format("onDrag: dx: %.2f. dy: %.2f", dx, dy));
        }

        View zoomView = getView();
        ViewParent parent = zoomView.getParent();
        boolean allowDrag = (mCurrentTouchPointCount >= mAllowDragMinTouchPointCount);
        if(!allowDrag) {
            if (null != parent) {
                parent.requestDisallowInterceptTouchEvent(true);
            }
            return;
        }

        mSuppMatrix.postTranslate(dx, getCoordsOrientationY() * dy);
        checkAndDisplayMatrix();

        /**
         * Here we decide whether to let the View's parent to start taking
         * over the touch event.
         *
         * First we check whether this function is enabled. We never want the
         * parent to take over if we're scaling. We then check the edge we're
         * on, and the direction of the scroll (i.e. if we're pulling against
         * the edge, aka 'overscrolling', let the parent take over).
         */
        if (mAllowParentInterceptOnEdge && !mScaleDragDetector.isScaling() && !mBlockParentIntercept) {
            if (mScrollEdge == EDGE_BOTH
                    || (mScrollEdge == EDGE_LEFT && dx >= 1f)
                    || (mScrollEdge == EDGE_RIGHT && dx <= -1f)) {
                if (null != parent)
                    parent.requestDisallowInterceptTouchEvent(false);
            }
        } else {
            if (null != parent) {
                parent.requestDisallowInterceptTouchEvent(true);
            }
        }
    }

    private int mAllowDragMinTouchPointCount = 0;
    private int mCurrentTouchPointCount = 0;
    public void setAllowDragMinTouchPointCount(int minPointCount) {
        mAllowDragMinTouchPointCount = minPointCount;
    }

    @Override
    public void onFling(float startX, float startY, float velocityX,
                        float velocityY) {
        if (DEBUG) {
            Log.d(
                    LOG_TAG,
                    "onFling. sX: " + startX + " sY: " + startY + " Vx: "
                            + velocityX + " Vy: " + velocityY);
        }
//        View zoomView = getView();
//        mCurrentFlingRunnable = new FlingRunnable(zoomView.getContext());
//        mCurrentFlingRunnable.fling(getViewWidth(zoomView),
//                getViewHeight(zoomView), (int) velocityX, (int) velocityY);
//        zoomView.post(mCurrentFlingRunnable);
    }

    @Override
    public void onGlobalLayout() {
        View zoomView = getView();

        if (null != zoomView) {
            if (mZoomEnabled) {
                final int top = zoomView.getTop();
                final int right = zoomView.getRight();
                final int bottom = zoomView.getBottom();
                final int left = zoomView.getLeft();

                /**
                 * We need to check whether the View's bounds have changed.
                 * This would be easier if we targeted API 11+ as we could just use
                 * View.OnLayoutChangeListener. Instead we have to replicate the
                 * work, keeping track of the View's bounds and then checking
                 * if the values change.
                 */
                if (top != mIvTop || bottom != mIvBottom || left != mIvLeft
                        || right != mIvRight) {
                    // Update our base matrix, as the bounds have changed
                    updateBaseMatrix();

                    // Update values as something has changed
                    mIvTop = top;
                    mIvRight = right;
                    mIvBottom = bottom;
                    mIvLeft = left;
                }
            } else {
                updateBaseMatrix();
            }
        }
    }

    @Override
    public void onScale(float scaleFactor, float focusX, float focusY) {
        if (DEBUG) {
            Log.d(
                    LOG_TAG,
                    String.format("onScale: scale: %.2f. fX: %.2f. fY: %.2f",
                            scaleFactor, focusX, focusY));
        }

        if (getScale() < mOverMaxScale || scaleFactor < 1f) {
            if(mOverMinScale > 0) {
                mTempSuppMatrix.set(mSuppMatrix);
                mTempSuppMatrix.postScale(scaleFactor, scaleFactor, focusX, focusY);
                if (getScale(mTempSuppMatrix) < mOverMinScale) {
                    return;
                }
            }

            if (null != mScaleChangeListener) {
                mScaleChangeListener.onScaleChange(scaleFactor, focusX, focusY);
            }
            mLastFocusCenter.x = focusX;
            mLastFocusCenter.y = focusY;
            mSuppMatrix.postScale(scaleFactor, scaleFactor, focusX, focusY);
            checkAndDisplayMatrix();
        }
    }

    public void setOverMinScale(float scale) {
        mOverMinScale = scale;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouch(View v, MotionEvent ev) {
        boolean handled = false;
        int action = ev.getAction() & MotionEvent.ACTION_MASK;

        try {

            if (mZoomEnabled && v == mView.get() && hasDrawable(asInterface())) {
                ViewParent parent = v.getParent();
                switch (action) {
                    case ACTION_DOWN:
                        // First, disable the Parent from intercepting the touch
                        // event
                        if (null != parent) {
                            parent.requestDisallowInterceptTouchEvent(true);
                        } else {
                            Log.i(LOG_TAG, "onTouch getParent() returned null");
                        }

                        // If we're flinging, and the user presses down, cancel
                        // fling
                        cancelFling();
                        break;

                    case ACTION_CANCEL:
                    case ACTION_UP:
                        // If the user has zoomed less than min scale, zoom back
                        // to min scale
                        float currScale = getScale();
                        if (currScale < mMinScale) {
                            if(mAutoZoomBackEnabled) {
                                RectF rect = getDisplayRect();
                                if (null != rect) {
                                    v.post(new AnimatedZoomRunnable(getScale(), mMinScale,
                                            rect.centerX(), rect.centerY()));
                                    handled = true;
                                }
                            }
                        } else if (currScale > mMaxScale) {
                            // 1. get last focus x,y
                            PointF focusCenter = mLastFocusCenter;
                            if (null == focusCenter || (0 == focusCenter.x && 0 == focusCenter.y)) {
                                focusCenter = minus.android.support.view.Compat.getScaleFocusXY(ev);
                            }
                            // 2. compute current focus x,y
                            if (null == focusCenter || (0 == focusCenter.x && 0 == focusCenter.y)) {
                                RectF rect = getDisplayRect();
                                if (null != focusCenter) {
                                    focusCenter.x = rect.centerX();
                                    focusCenter.y = rect.centerY();
                                }
                            }
                            // 3. compute current center x,y
                            if (null == focusCenter || (0 == focusCenter.x && 0 == focusCenter.y)) {
                                focusCenter = null;
                            }
                            if (null != focusCenter) {
                                v.post(new AnimatedZoomRunnable(getScale(), mMaxScale,
                                        focusCenter.x, focusCenter.y));
                                handled = true;
                            } else {
                                // 4. if fail then next do not over scale
                                mOverMaxScale = mMaxScale;
                            }
                        }
                        break;
                }

                // Try the Scale/Drag detector
                if (null != mScaleDragDetector) {
                    boolean wasScaling = mScaleDragDetector.isScaling();
                    boolean wasDragging = mScaleDragDetector.isDragging();

                    handled = mScaleDragDetector.onTouchEvent(ev);

                    boolean didntScale = !wasScaling && !mScaleDragDetector.isScaling();
                    boolean didntDrag = !wasDragging && !mScaleDragDetector.isDragging();

                    mBlockParentIntercept = didntScale && didntDrag;
                }

                // Check to see if the user double tapped
                if (null != mGestureDetector && mGestureDetector.onTouchEvent(ev)) {
                    handled = true;
                }

            }
        } finally {
            if(action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                mCurrentTouchPointCount = 0;
            } else {
                mCurrentTouchPointCount = ev.getPointerCount();
            }
            if(null != mPhotoTouchListener) {
                mPhotoTouchListener.onPhotoTouchEvent(getDisplayRect(), ev);
            }
        }

        return handled;
    }

    public void setAllowParentInterceptOnEdge(boolean allow) {
        mAllowParentInterceptOnEdge = allow;
    }

    public void setMinimumScale(float minimumScale) {
        checkZoomLevels(minimumScale, mMidScale, mMaxScale);
        mMinScale = minimumScale;
    }

    public void setMediumScale(float mediumScale) {
        checkZoomLevels(mMinScale, mediumScale, mMaxScale);
        mMidScale = mediumScale;
    }

    public void setMaximumScale(float maximumScale) {
        checkZoomLevels(mMinScale, mMidScale, maximumScale);
        mMaxScale = maximumScale;
    }

    public void setScaleLevels(float minimumScale, float mediumScale, float maximumScale) {
        checkZoomLevels(minimumScale, mediumScale, maximumScale);
        mMinScale = minimumScale;
        mMidScale = mediumScale;
        mMaxScale = maximumScale;
    }

    public void setOnLongClickListener(OnLongClickListener listener) {
        mLongClickListener = listener;
    }

    public void setOnMatrixChangeListener(OnMatrixChangedListener listener) {
        mMatrixChangeListener = listener;
    }

    public void setAutoZoomBackEnabled(boolean enabled) {
        mAutoZoomBackEnabled = enabled;
    }

    public void setOnPhotoTouchEvent(OnPhotoTouchEvent l) {
        mPhotoTouchListener = l;
    }

    public void setOnPhotoTapListener(OnPhotoTapListener listener) {
        mPhotoTapListener = listener;
    }

    public OnPhotoTapListener getOnPhotoTapListener() {
        return mPhotoTapListener;
    }

    public void setOnViewTapListener(OnViewTapListener listener) {
        mViewTapListener = listener;
    }

    public OnViewTapListener getOnViewTapListener() {
        return mViewTapListener;
    }

    public void setScale(float scale) {
        setScale(scale, false);
    }

    public void setScale(float scale, boolean animate) {
        View zoomView = getView();

        if (null != zoomView) {
            setScale(scale,
                    (zoomView.getRight()) / 2,
                    (zoomView.getBottom()) / 2,
                    animate);
        }
    }

    public void setScale(float scale, float focalX, float focalY,
                         boolean animate) {
        View zoomView = getView();

        if (null != zoomView) {
            // Check to see if the scale is within bounds
            if (scale < mMinScale || scale > mMaxScale) {
                Log.i(LOG_TAG,
                                "Scale must be within the range of minScale and maxScale");
                return;
            }

            if (animate) {
                zoomView.post(new AnimatedZoomRunnable(getScale(), scale,
                        focalX, focalY));
            } else {
                mSuppMatrix.setScale(scale, scale, focalX, focalY);
                checkAndDisplayMatrix();
            }
        }
    }

    public void setScaleType(ScaleType scaleType) {
        if (isSupportedScaleType(scaleType) && scaleType != mScaleType) {
            mScaleType = scaleType;

            // Finally update
            update();
        }
    }

    public boolean isZoomable() {
        return mZoomEnabled;
    }

    public void setZoomable(boolean zoomable) {
        mZoomEnabled = zoomable;
        update();
    }

    public void update() {
        IZoomView zoomView = asInterface();

        if (null != zoomView) {
            if (mZoomEnabled) {
                // Make sure we using MATRIX Scale Type
                setViewScaleTypeMatrix(zoomView);

                // Update the base matrix using the current drawable
                updateBaseMatrix();
            } else {
                // Reset the Matrix...
                resetMatrix();
            }
        }
    }

    public Matrix getDisplayMatrix() {
        return new Matrix(getDrawMatrix());
    }

    public Matrix getDrawMatrix() {
        mDrawMatrix.set(mBaseMatrix);
        mDrawMatrix.postConcat(mSuppMatrix);
        return mDrawMatrix;
    }

    private void cancelFling() {
        if (null != mCurrentFlingRunnable) {
            mCurrentFlingRunnable.cancelFling();
            mCurrentFlingRunnable = null;
        }
    }

    /**
     * Helper method that simply checks the Matrix, and then displays the result
     */
    private void checkAndDisplayMatrix() {
        if (checkMatrixBounds()) {
            setViewMatrix(getDrawMatrix());
        }
    }

    private void checkViewScaleType() {
        IZoomView zoomView = asInterface();

        /**
         * PhotoView's getScaleType() will just divert to this.getScaleType() so
         * only call if we're not attached to a PhotoView.
         */
		if (null != zoomView && !ScaleType.MATRIX.equals(zoomView.getScaleType())) {
			throw new IllegalStateException(
					"The View's ScaleType has been changed since attaching a PhotoViewAttacher");
		}
    }

    private boolean checkMatrixBounds() {
        final View zoomView = getView();
        if (null == zoomView) {
            return false;
        }

        final RectF rect = getDisplayRect(getDrawMatrix());
        if (null == rect) {
            return false;
        }

        final float height = rect.height(), width = rect.width();
        float deltaX = 0, deltaY = 0;

        final int viewHeight = getViewHeight(zoomView);
        if (height <= viewHeight) {
            switch (mScaleType) {
                case FIT_START:
                    deltaY = -rect.top;
                    break;
                case FIT_END:
                    deltaY = viewHeight - height - rect.top;
                    break;
                default:
                    deltaY = (viewHeight - height) / 2 - rect.top;
                    break;
            }
        } else if (rect.top > 0) {
            deltaY = -rect.top;
        } else if (rect.bottom < viewHeight) {
            deltaY = viewHeight - rect.bottom;
        }

        final int viewWidth = getViewWidth(zoomView);
        if (width <= viewWidth) {
            switch (mScaleType) {
                case FIT_START:
                    deltaX = -rect.left;
                    break;
                case FIT_END:
                    deltaX = viewWidth - width - rect.left;
                    break;
                default:
                    deltaX = (viewWidth - width) / 2 - rect.left;
                    break;
            }
            mScrollEdge = EDGE_BOTH;
        } else if (rect.left > 0) {
            mScrollEdge = EDGE_LEFT;
            deltaX = -rect.left;
        } else if (rect.right < viewWidth) {
            deltaX = viewWidth - rect.right;
            mScrollEdge = EDGE_RIGHT;
        } else {
            mScrollEdge = EDGE_NONE;
        }

        // Finally actually translate the matrix
        mSuppMatrix.postTranslate(deltaX, deltaY);
        return true;
    }

    /**
     * Helper method that maps the supplied Matrix to the current Drawable
     *
     * @param matrix - Matrix to map Drawable against
     * @return RectF - Displayed Rectangle
     */
    private RectF getDisplayRect(Matrix matrix) {
        IZoomView zoomView = asInterface();

        if (null != zoomView) {
            if (zoomView.hasDrawable()) {
                zoomView.getDisplayRect(mDisplayRect);
                matrix.mapRect(mDisplayRect);
                return mDisplayRect;
            }
        }
        return null;
    }

    public Bitmap getVisibleRectangleBitmap() {
        View zoomView = getView();
        return zoomView == null ? null : zoomView.getDrawingCache();
    }

    public void setZoomTransitionDuration(int milliseconds) {
        if (milliseconds < 0)
            milliseconds = DEFAULT_ZOOM_DURATION;
        this.ZOOM_DURATION = milliseconds;
    }

    /**
     * Helper method that 'unpacks' a Matrix and returns the required value
     *
     * @param matrix     - Matrix to unpack
     * @param whichValue - Which value from Matrix.M* to return
     * @return float - returned value
     */
    private float getValue(Matrix matrix, int whichValue) {
        matrix.getValues(mMatrixValues);
        return mMatrixValues[whichValue];
    }

    /**
     * Resets the Matrix back to FIT_CENTER, and then displays it.s
     */
    private void resetMatrix() {
        mSuppMatrix.reset();
        setViewMatrix(getDrawMatrix());
        checkMatrixBounds();
    }

    private void setViewMatrix(Matrix matrix) {
        IZoomView zoomView = asInterface();
        if (null != zoomView) {

            checkViewScaleType();
            zoomView.setImageMatrix(matrix);

            // Call MatrixChangedListener if needed
            if (null != mMatrixChangeListener) {
                RectF displayRect = getDisplayRect(matrix);
                if (null != displayRect) {
                    mMatrixChangeListener.onMatrixChanged(displayRect);
                }
            }
        }
    }

    /**
     * Calculate Matrix for FIT_CENTER
     *
     */
    private void updateBaseMatrix() {
        View zoomView = getView();
        IZoomView zoomInterface = asInterface();
        if (null == zoomView || null == zoomInterface) {
            return;
        }

        final float viewWidth = getViewWidth(zoomView);
        final float viewHeight = getViewHeight(zoomView);
        final int drawableWidth = zoomInterface.getIntrinsicWidth();
        final int drawableHeight = zoomInterface.getIntrinsicHeight();

        mBaseMatrix.reset();

        final float widthScale = viewWidth / drawableWidth;
        final float heightScale = viewHeight / drawableHeight;

        if (mScaleType == ScaleType.CENTER) {
            mBaseMatrix.postTranslate((viewWidth - drawableWidth) / 2F,
                    (viewHeight - drawableHeight) / 2F);

        } else if (mScaleType == ScaleType.CENTER_CROP) {
            float scale = Math.max(widthScale, heightScale);
            mBaseMatrix.postScale(scale, scale);
            mBaseMatrix.postTranslate((viewWidth - drawableWidth * scale) / 2F,
                    (viewHeight - drawableHeight * scale) / 2F);

        } else if (mScaleType == ScaleType.CENTER_INSIDE) {
            float scale = Math.min(1.0f, Math.min(widthScale, heightScale));
            mBaseMatrix.postScale(scale, scale);
            mBaseMatrix.postTranslate((viewWidth - drawableWidth * scale) / 2F,
                    (viewHeight - drawableHeight * scale) / 2F);

        } else {
            RectF mTempSrc = new RectF(0, 0, drawableWidth, drawableHeight);
            RectF mTempDst = new RectF(0, 0, viewWidth, viewHeight);

            switch (mScaleType) {
                case FIT_CENTER:
                    mBaseMatrix
                            .setRectToRect(mTempSrc, mTempDst, ScaleToFit.CENTER);
                    break;

                case FIT_START:
                    mBaseMatrix.setRectToRect(mTempSrc, mTempDst, ScaleToFit.START);
                    break;

                case FIT_END:
                    mBaseMatrix.setRectToRect(mTempSrc, mTempDst, ScaleToFit.END);
                    break;

                case FIT_XY:
                    mBaseMatrix.setRectToRect(mTempSrc, mTempDst, ScaleToFit.FILL);
                    break;

                default:
                    break;
            }
        }

        resetMatrix();
    }

    private int getViewWidth(View zoomView) {
        if (null == zoomView)
            return 0;
        return zoomView.getWidth() - zoomView.getPaddingLeft() - zoomView.getPaddingRight();
    }

    private int getViewHeight(View zoomView) {
        if (null == zoomView)
            return 0;
        return zoomView.getHeight() - zoomView.getPaddingTop() - zoomView.getPaddingBottom();
    }

    /**
     * Interface definition for a callback to be invoked when the internal Matrix has changed for
     * this View.
     *
     * @author Chris Banes
     */
    public static interface OnMatrixChangedListener {
        /**
         * Callback for when the Matrix displaying the Drawable has changed. This could be because
         * the View's bounds have changed, or the user has zoomed.
         *
         * @param rect - Rectangle displaying the Drawable's new bounds.
         */
        void onMatrixChanged(RectF rect);
    }

    public interface OnPhotoTouchEvent {
        void onPhotoTouchEvent(RectF bounds, MotionEvent ev);
    }

    /**
     * Interface definition for callback to be invoked when attached View scale changes
     *
     * @author Marek Sebera
     */
    public static interface OnScaleChangeListener {
        /**
         * Callback for when the scale changes
         *
         * @param scaleFactor the scale factor (less than 1 for zoom out, greater than 1 for zoom in)
         * @param focusX      focal point X position
         * @param focusY      focal point Y position
         */
        void onScaleChange(float scaleFactor, float focusX, float focusY);
    }

    /**
     * Interface definition for a callback to be invoked when the Photo is tapped with a single
     * tap.
     *
     * @author Chris Banes
     */
    public static interface OnPhotoTapListener {

        /**
         * A callback to receive where the user taps on a photo. You will only receive a callback if
         * the user taps on the actual photo, tapping on 'whitespace' will be ignored.
         *
         * @param view - View the user tapped.
         * @param x    - where the user tapped from the of the Drawable, as percentage of the
         *             Drawable width.
         * @param y    - where the user tapped from the top of the Drawable, as percentage of the
         *             Drawable height.
         */
        void onPhotoTap(View view, float x, float y);
    }

    /**
     * Interface definition for a callback to be invoked when the View is tapped with a single
     * tap.
     *
     * @author Chris Banes
     */
    public static interface OnViewTapListener {

        /**
         * A callback to receive where the user taps on a View. You will receive a callback if
         * the user taps anywhere on the view, tapping on 'whitespace' will not be ignored.
         *
         * @param view - View the user tapped.
         * @param x    - where the user tapped from the left of the View.
         * @param y    - where the user tapped from the top of the View.
         */
        void onViewTap(View view, float x, float y);
    }

    private class AnimatedZoomRunnable implements Runnable {

        private final float mFocalX, mFocalY;
        private final long mStartTime;
        private final float mZoomStart, mZoomEnd;

        public AnimatedZoomRunnable(final float currentZoom, final float targetZoom,
                                    final float focalX, final float focalY) {
            mFocalX = focalX;
            mFocalY = focalY;
            mStartTime = System.currentTimeMillis();
            mZoomStart = currentZoom;
            mZoomEnd = targetZoom;
        }

        @Override
        public void run() {
            View zoomView = getView();
            if (zoomView == null) {
                return;
            }

            float t = interpolate();
            float scale = mZoomStart + t * (mZoomEnd - mZoomStart);
            float deltaScale = scale / getScale();

            onScale(deltaScale, mFocalX, mFocalY);

            // We haven't hit our target scale yet, so post ourselves again
            if (t < 1f) {
                minus.android.support.view.Compat.postOnAnimation(zoomView, this);
            }
        }

        private float interpolate() {
            float t = 1f * (System.currentTimeMillis() - mStartTime) / ZOOM_DURATION;
            t = Math.min(1f, t);
            t = sInterpolator.getInterpolation(t);
            return t;
        }
    }

    private class FlingRunnable implements Runnable {

        private final OverScroller mScroller;
        private int mCurrentX, mCurrentY;

        public FlingRunnable(Context context) {
            mScroller = new OverScroller(context);
        }

        public void cancelFling() {
            if (DEBUG) {
                Log.d(LOG_TAG, "Cancel Fling");
            }
            mScroller.forceFinished(true);
        }

        public void fling(int viewWidth, int viewHeight, int velocityX,
                          int velocityY) {
            final RectF rect = getDisplayRect();
            if (null == rect) {
                return;
            }

            final int startX = Math.round(-rect.left);
            final int minX, maxX, minY, maxY;

            if (viewWidth < rect.width()) {
                minX = 0;
                maxX = Math.round(rect.width() - viewWidth);
            } else {
                minX = maxX = startX;
            }

            final int startY = Math.round(-rect.top);
            if (viewHeight < rect.height()) {
                minY = 0;
                maxY = Math.round(rect.height() - viewHeight);
            } else {
                minY = maxY = startY;
            }

            mCurrentX = startX;
            mCurrentY = startY;

            if (DEBUG) {
                Log.d(
                        LOG_TAG,
                        "fling. StartX:" + startX + " StartY:" + startY
                                + " MaxX:" + maxX + " MaxY:" + maxY);
            }

            // If we actually can move, fling the scroller
            if (startX != maxX || startY != maxY) {
                mScroller.fling(startX, startY, velocityX, velocityY, minX,
                        maxX, minY, maxY, 0, 0);
            }
        }

        @Override
        public void run() {
            if (mScroller.isFinished()) {
                return; // remaining post that should not be handled
            }

            View zoomView = getView();
            if (null != zoomView && mScroller.computeScrollOffset()) {

                final int newX = mScroller.getCurrX();
                final int newY = mScroller.getCurrY();

                if (DEBUG) {
                    Log.d(
                            LOG_TAG,
                            "fling run(). CurrentX:" + mCurrentX + " CurrentY:"
                                    + mCurrentY + " NewX:" + newX + " NewY:"
                                    + newY);
                }

                mSuppMatrix.postTranslate(mCurrentX - newX, mCurrentY - newY);
                setViewMatrix(getDrawMatrix());

                mCurrentX = newX;
                mCurrentY = newY;

                // Post On animation
                minus.android.support.view.Compat.postOnAnimation(zoomView, this);
            }
        }
    }

    class DefaultOnDoubleTapListener implements GestureDetector.OnDoubleTapListener {

        private ZoomViewHelper photoViewAttacher;

        /**
         * Default constructor
         *
         * @param photoViewAttacher PhotoViewAttacher to bind to
         */
        public DefaultOnDoubleTapListener(ZoomViewHelper photoViewAttacher) {
            setPhotoViewAttacher(photoViewAttacher);
        }

        /**
         * Allows to change PhotoViewAttacher within range of single instance
         *
         * @param newPhotoViewAttacher PhotoViewAttacher to bind to
         */
        public void setPhotoViewAttacher(ZoomViewHelper newPhotoViewAttacher) {
            this.photoViewAttacher = newPhotoViewAttacher;
        }

        @Override
        public boolean onSingleTapConfirmed(MotionEvent e) {
            if (this.photoViewAttacher == null)
                return false;

            View zoomView = photoViewAttacher.getView();

            if (null != photoViewAttacher.getOnPhotoTapListener()) {
                final RectF displayRect = photoViewAttacher.getDisplayRect();

                if (null != displayRect) {
                    final float x = e.getX(), y = e.getY();

                    // Check to see if the user tapped on the photo
                    if (displayRect.contains(x, y)) {

                        float xResult = (x - displayRect.left)
                                / displayRect.width();
                        float yResult = (y - displayRect.top)
                                / displayRect.height();

                        photoViewAttacher.getOnPhotoTapListener().onPhotoTap(zoomView, xResult, yResult);
                        return true;
                    }
                }
            }
            if (null != photoViewAttacher.getOnViewTapListener()) {
                photoViewAttacher.getOnViewTapListener().onViewTap(zoomView, e.getX(), e.getY());
            }

            return false;
        }

        @Override
        public boolean onDoubleTap(MotionEvent ev) {
            if (photoViewAttacher == null)
                return false;

            try {
                float scale = photoViewAttacher.getScale();
                float x = ev.getX();
                float y = ev.getY();

//            if (scale < photoViewAttacher.getMediumScale()) {
//                photoViewAttacher.setScale(photoViewAttacher.getMediumScale(), x, y, true);
//            } else if (scale >= photoViewAttacher.getMediumScale() && scale < photoViewAttacher.getMaximumScale()) {
//                photoViewAttacher.setScale(photoViewAttacher.getMaximumScale(), x, y, true);
//            } else {
//                photoViewAttacher.setScale(photoViewAttacher.getMini mumScale(), x, y, true);
//            }
                if (scale != 1.f) {
                    photoViewAttacher.setScale(1.f, x, y, true);
                } else {
                    photoViewAttacher.setScale(photoViewAttacher.getMaximumScale(), x, y, true);
                }
            } catch (ArrayIndexOutOfBoundsException e) {
                // Can sometimes happen when getX() and getY() is called
            }

            return true;
        }

        @Override
        public boolean onDoubleTapEvent(MotionEvent e) {
            // Wait for the confirmed onDoubleTap() instead
            return false;
        }

    }
    
}
