package org.oep.pong;

import java.util.Random;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Paint.Style;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnCompletionListener;
import android.os.Handler;
import android.os.Message;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnKeyListener;
import android.view.View.OnTouchListener;
import android.widget.Toast;

/**
 * This class is the main viewing window for the Pong game. All the game's
 * logic takes place within this class as well.
 * @author OEP
 *
 */
public class PongView extends View implements OnTouchListener, OnKeyListener, OnCompletionListener {
	/** Debug tag */
	private static final String TAG = "PongView";
	protected static final int FPS = 30;
	
	/**
	 * This is mostly deprecated but kept around if the need
	 * to add more game states comes around.
	 */
	private State mCurrentState = State.Running;
	private State mLastState = State.Stopped;
	public static enum State { Running, Stopped}

	/** Flag that marks this view as initialized */
	private boolean mInitialized = false;
	
	/** Starts a new round when set to true */
	private boolean mNewRound = true;
	
	/** Shows the title screen when set to true */
	private boolean mShowTitle = true;
	
	/** Keeps the game thread alive */
	private boolean mContinue = true;
	
	/** Mutes sounds when true */
	private boolean mMuted = false;

	private Paddle mRed, mBlue;
	
	/** Touch boxes for various functions. These are assigned in initialize() */
	private Rect mPauseTouchBox;

	/** Timestamp of the last frame created */
	private long mLastFrame = 0;

	/** Speed of the paddles */
	private int mPaddleSpeed = 2;
	
	protected Ball mBall = new Ball();

	/** Random number generator */
	private static final Random RNG = new Random();
	
	/** Paint object */
	private final Paint mPaint = new Paint();

	/** Padding for touch zones and paddles */
	private static final int PADDING = 3;
	
	/** Scrollwheel sensitivity */
	private static final int SCROLL_SENSITIVITY = 80;

	/** Redraws the screen according to FPS */
	private RefreshHandler mRedrawHandler = new RefreshHandler();
	
	/** Tone for a wall hit */
	private MediaPlayer mWallHit;
	
	/** Tone for a paddle hit */
	private MediaPlayer mPaddleHit;
	
	/** Tone for when the ball is missed */
	private MediaPlayer mMissTone;
	
	/** Tone for when a game is won */
	private MediaPlayer mWinTone;

	/**
	 * An overloaded class that repaints this view in a separate thread.
	 * Calling PongView.update() should initiate the thread.
	 * @author OEP
	 *
	 */
	class RefreshHandler extends Handler {
		@Override
		public void handleMessage(Message msg) {
			PongView.this.update();
			PongView.this.invalidate(); // Mark the view as 'dirty'
		}
		
		public void sleep(long delay) {
			this.removeMessages(0);
			this.sendMessageDelayed(obtainMessage(0), delay);
		}
	}

    /**
     * Creates a new PongView within some context
     * @param context
     * @param attrs
     */
    public PongView(Context context, AttributeSet attrs) {
        super(context, attrs);
        initPongView();
    }

    public PongView(Context context, AttributeSet attrs, int defStyle) {
    	super(context, attrs, defStyle);
    	initPongView();
    }
    
    /**
     * The main loop. Call this to update the game state.
     */
    public void update() {
    	if(getHeight() == 0 || getWidth() == 0) {
    		mRedrawHandler.sleep(1000 / FPS);
    		return;
    	}
    	
    	if(!mInitialized) {
    		nextRound();
    		newGame();
    		mInitialized = true;
    	}
    	
    	long now = System.currentTimeMillis();
    	if(gameRunning() && mCurrentState != State.Stopped) {
	    	if(now - mLastFrame >= 1000 / FPS) {
	    		if(mNewRound) {
	    			nextRound();
	    			mNewRound = false;
	    		}
	    		doGameLogic();
	    	}
    	}
    	
    	// We will take this much time off of the next update() call to normalize for
    	// CPU time used updating the game state.
    	
    	if(mContinue) {
    		long diff = System.currentTimeMillis() - now;
    		mRedrawHandler.sleep(Math.max(0, (1000 / FPS) - diff) );
    	}
    }

    /**
     * All of the game's logic (per game iteration) is in this function.
     * Given some initial game state, it computes the next game state.
     */
	private void doGameLogic() {
		float py = mBall.y;
		
		mBall.move();
		
		// Shake it up if it appears to not be moving vertically
		if(py == mBall.y) {
			mBall.randomAngle();
		}
		
		// Do some basic paddle AI
		if(!mRed.player) doAI(mRed, mBlue);
		else mRed.move();
		
		if(!mBlue.player) doAI(mBlue, mRed);
		else mBlue.move();
		
		// See if all is lost
		if(mBall.y >= getHeight()) {
			mNewRound = true;
			mBlue.loseLife();
			
			if(mBlue.living() || mShowTitle) playSound(mMissTone);
			else playSound(mWinTone);
		}
		else if (mBall.y <= 0) {
			mNewRound = true;
			mRed.loseLife();
			if(mRed.living() || mShowTitle) playSound(mMissTone);
			else playSound(mWinTone);
		}
		
		handleBounces();
	}
	
	protected void handleBounces() {
		// Handle bouncing off of a wall
		if(mBall.x <= Ball.RADIUS || mBall.x >= getWidth() - Ball.RADIUS) {
			mBall.bounceWall();
			if(mBall.x == Ball.RADIUS)
				mBall.x++;
			else
				mBall.x--;
		}
		
		// Bouncing off the paddles
		if(mBall.goingUp() && mBall.collides(mRed) ) {
			mBall.bouncePaddle(mRed);
			increaseDifficulty();
		}
		else if(mBall.goingDown() && mBall.collides(mBlue)) {
			mBall.bouncePaddle(mBlue);
			increaseDifficulty();
		}	
	}
	
	/**
	 * A generalized Pong AI player. Takes a Rect object and a Ball, computes where the ball will
	 * be when ball.y == rect.y, and tries to move toward that x-coordinate. If the ball is moving
	 * straight it will try to clip the ball with the edge of the paddle.
	 * @param red
	 */
	private void doAI(Paddle red, Paddle blue) {
		Ball ball = new Ball(mBall);
		
		// Special case: move torward the center if the ball is blinking
		if(mBall.serving()) {
			red.destination = getWidth() / 2;
			red.move(true);
			return;
		}
		
		// Something is wrong if vy = 0.. let's wait until things fix themselves
		if(ball.vy == 0) return;
		
		// Y-Distance from ball to Rect 'cpu'
		float cpuDist = Math.abs(ball.y - red.centerY());
		// Y-Distance to opponent.
		float oppDist = Math.abs( ball.y - blue.centerY() );
		
		// Distance between two paddles.
		float paddleDistance = Math.abs(red.centerY() - blue.centerY());
		
		// Is the ball coming at us?
		boolean coming = (red.centerY() < ball.y && ball.vy < 0)
			|| (red.centerY() > ball.y && ball.vy > 0);
		
		// Total amount of x-distance the ball covers
		float total = ((((coming) ? cpuDist : oppDist + paddleDistance)) / Math.abs(ball.vy)) * Math.abs( ball.vx );
		
		// Playable width of the stage
		float playWidth = getWidth() - 2 * Ball.RADIUS;
		
		
		float wallDist = (ball.goingLeft()) ? ball.x - Ball.RADIUS : playWidth - ball.x + Ball.RADIUS;
		
		// Effective x-translation left over after first bounce 
		float remains = (total - wallDist) % playWidth;
		
		// Bounces the ball will incur
		int bounces = (int) ((total) / playWidth);
		
		boolean left = (bounces % 2 == 0) ? !ball.goingLeft() : ball.goingLeft();
		
		red.destination = getWidth() / 2;
		
		// Now we need to compute the final x. That's all that matters.
		if(bounces == 0) {
			red.destination = (int) (ball.x + total * Math.signum(ball.vx));
		}
		else if(left) {
			red.destination = (int) (Ball.RADIUS + remains);
		}
		else { // The ball is going right...
			red.destination = (int) ((Ball.RADIUS + playWidth) - remains);
		}
		
		/*if(cpu == mBluePaddleRect) {
			projx = (int) prediction;
			projy = mBluePaddleRect.centerY();
			
			Log.d(TAG, "Distance to blue: " + oppDist);
			Log.d(TAG, "Distance to red: " + cpuDist);
			Log.d(TAG, "Distance from red to blue: " + paddleDistance);
			
			Log.d(TAG, String.format("Ball V: <%f, %f>\n", ball.vx, ball.vy));
			Log.d(TAG, String.format("Ball P: <%f, %f>\n", ball.x, ball.y));
			
			Log.d(TAG, "Total x-dist: " + total);
			Log.d(TAG, "Total wall-dist: " + wallDist);
			Log.d(TAG, "Total playWidth: " + playWidth);
			Log.d(TAG, "Total remains: " + remains);
			
			
			Log.d(TAG, "Bounces: " + bounces);
			
			Log.d(TAG, "Prediction: " + prediction);
			
			System.currentTimeMillis();
		}*/
		
		// Try to give it a little kick if vx = 0
		int salt = (int) (System.currentTimeMillis() / 10000);
		Random r = new Random((long) (red.centerY() + ball.vx + ball.vy + salt));
		int width = red.getWidth();
		red.destination += r.nextInt(2 * width - (width / 5)) - width + (width / 10);
		red.move(true);
	}
	
	/**
	 * Knocks up the framerate a bit to keep it difficult.
	 */
	private void increaseDifficulty() {
		mBall.speed++;
	}

	/**
	 * Set the state, start a new round, start the loop if needed.
	 * @param next, the next state
	 */
	public void setMode(State next) {
    	mCurrentState = next;
    	nextRound();
    	update();
    }
    
    /**
     * Set the paddles to their initial states and as well the ball.
     */
    private void initPongView() {
    	setOnTouchListener(this);
    	setOnKeyListener(this);
    	setFocusable(true);
    	resetPaddles();
    	resetBall();
    	
    	mWallHit = loadSound(R.raw.wall);
    	mPaddleHit = loadSound(R.raw.paddle);
    	mMissTone = loadSound(R.raw.ballmiss);
    	mWinTone = loadSound(R.raw.wintone);
    	
    	// Grab the muted preference
    	Context ctx = this.getContext();
    	SharedPreferences settings = ctx.getSharedPreferences(Pong.DB_PREFS, 0);
    	mMuted = settings.getBoolean(Pong.PREF_MUTED, mMuted);
    }
    
    /**
     * Reset the paddles/touchboxes/framespersecond/ballcounter for the next round.
     */
    private void nextRound() {
    	int min = Math.min(getWidth() / 4, getHeight() / 4);
    	int xmid = getWidth() / 2;
    	int ymid = getHeight() / 2;
    	mPauseTouchBox = new Rect(xmid - min, ymid - min, xmid + min, ymid + min);
    	
    	resetPaddles();
    	resetBall();
    	mBall.speed = Ball.SPEED;
    	mBall.pause();
    }
    
	/**
     * Reset paddles to an initial state.
     */
    private void resetPaddles() {
    	mRed = new Paddle(Color.RED, PADDING);
    	mBlue = new Paddle(Color.BLUE, getHeight() - PADDING - Paddle.PADDLE_THICKNESS);
    	
    	mRed.setTouchbox( new Rect(0,0,getWidth(),getHeight() / 8) );
    	mBlue.setTouchbox( new Rect(0, 7 * getHeight() / 8, getWidth(), getHeight()) );
    	
    	mRed.destination = getWidth() / 2;
    	mBlue.destination = getWidth() / 2;
    }
    
    /**
     * Reset ball to an initial state
     */
    private void resetBall() {
    	mBall.x = getWidth() / 2;
    	mBall.y = getHeight() / 2;
    	mBall.randomAngle();
    	mBall.pause();
    }
    
    protected float bound(float x, float low, float hi) {
    	return Math.max(low, Math.min(x, hi));
    }
    
    /**
     * Use for keeping track of a position.
     * @author pkilgo
     *
     */
    class Point {
    	private int x, y;
    	Point() {
    		x = 0; y = 0;
    	}
    	
    	Point(int x, int y) {
    		this.x = x; this.y = y;
    	}
    	
    	public int getX() { return x; }
    	public int getY() { return y ; }
    	public void set(double d, double e) { this.x = (int) d; this.y = (int) e; }
    	
    	public void translate(int i, int j) { this.x += i; this.y += j; }
    	
    	@Override
    	public String toString() {
    		return "Point: (" + x + ", " + y + ")";
    	}
    }
    
    public void onSizeChanged(int w, int h, int ow, int oh) {
    	mPaddleSpeed = Math.max(1, w / 160);
    }
    
    /**
     * Paints the game!
     */
    @Override
    public void onDraw(Canvas canvas) {
        super.onDraw(canvas);
    	Context context = getContext();
    	
        // Draw the paddles / touch boundaries
    	mRed.draw(canvas);
    	mBlue.draw(canvas);

    	// Draw touchboxes if needed
    	if(gameRunning() && mRed.player && mCurrentState == State.Running)
        	mRed.drawTouchbox(canvas);
        
        if(gameRunning() && mBlue.player && mCurrentState == State.Running)
        	mBlue.drawTouchbox(canvas);
        
        // Draw ball stuff
        mPaint.setStyle(Style.FILL);
        mPaint.setColor(Color.WHITE);
        
        mBall.draw(canvas);
        
        // If either is a not a player, blink and let them know they can join in!
        // This blinks with the ball.
        if(!mShowTitle && mBall.serving()) {
        	String join = context.getString(R.string.join_in);
        	int joinw = (int) mPaint.measureText(join);
        	
        	if(!mRed.player) {
        		mPaint.setColor(Color.RED);
        		canvas.drawText(join, getWidth() / 2 - joinw / 2, mRed.touchCenterY(), mPaint);
        	}
        	
        	if(!mBlue.player) {
        		mPaint.setColor(Color.BLUE);
        		canvas.drawText(join, getWidth() / 2 - joinw / 2, mBlue.touchCenterY(), mPaint);
        	}
        }
        
        // Show where the player can touch to pause the game
        if(!mShowTitle && mBall.serving()) {
        	String pause = context.getString(R.string.pause);
        	int pausew = (int) mPaint.measureText(pause);
        
        	mPaint.setColor(Color.GREEN);
        	mPaint.setStyle(Style.STROKE);
        	canvas.drawRect(mPauseTouchBox, mPaint);
        	canvas.drawText(pause, getWidth() / 2 - pausew / 2, getHeight() / 2, mPaint);
        }

    	// Paint a PAUSED message
        if(gameRunning() && mCurrentState == State.Stopped) {
        	String s = context.getString(R.string.paused);
        	int width = (int) mPaint.measureText(s);
        	int height = (int) (mPaint.ascent() + mPaint.descent()); 
        	mPaint.setColor(Color.WHITE);
        	canvas.drawText(s, getWidth() / 2 - width / 2, getHeight() / 2 - height / 2, mPaint);
        }
        
        // Draw a 'lives' counter
        if(!mShowTitle) {
        	mPaint.setColor(Color.WHITE);
        	mPaint.setStyle(Style.FILL_AND_STROKE);
        	for(int i = 0; i < mRed.getLives(); i++) {
        		canvas.drawCircle(Ball.RADIUS + PADDING + i * (2 * Ball.RADIUS + PADDING),
        				PADDING + Ball.RADIUS,
        				Ball.RADIUS,
        				mPaint);
        	}
        	
        	for(int i = 0; i < mBlue.getLives(); i++) {
        		canvas.drawCircle(Ball.RADIUS + PADDING + i * (2 * Ball.RADIUS + PADDING),
        				getHeight() - PADDING - Ball.RADIUS,
        				Ball.RADIUS,
        				mPaint);
        	}
        }
        
        // Announce the winner!
        if(!gameRunning()) {
        	mPaint.setColor(Color.GREEN);
        	String s = "You both lose";
        	
        	if(!mBlue.living()) {
        		s = context.getString(R.string.red_wins);
        		mPaint.setColor(Color.RED);
        	}
        	else if(!mRed.living()) {
        		s = context.getString(R.string.blue_wins);
        		mPaint.setColor(Color.BLUE);
        	}
        	
        	int width = (int) mPaint.measureText(s);
        	int height = (int) (mPaint.ascent() + mPaint.descent()); 
        	canvas.drawText(s, getWidth() / 2 - width / 2, getHeight() / 2 - height / 2, mPaint);
        }
        
        // Draw the Title text
        if(mShowTitle) {
        	Bitmap image = BitmapFactory.decodeResource(context.getResources(), R.drawable.pong);
        	
        	canvas.drawBitmap(image, getWidth() / 2 - image.getWidth() / 2, 
        			getHeight() / 2 - image.getHeight() / 2, mPaint);
        	
        	String prompt = context.getString(R.string.menu_prompt);
       	
        	mPaint.setColor(Color.WHITE);
        	
        	int nextLine = 3 * getHeight() / 4;
        	int w = (int) mPaint.measureText(prompt);
        	canvas.drawText(prompt, getWidth() / 2 - w / 2, nextLine, mPaint);
        }
    }

    /**
     * Touching is the method of movement. Touching the touchscreen, that is.
     * A player can join in simply by touching where they would in a normal
     * game.
     */
	public boolean onTouch(View v, MotionEvent mo) {
		if(v != this || !gameRunning() || mShowTitle) return false;
		
		// We want to support multiple touch and single touch
		InputHandler handle = InputHandler.getInstance();

		// Loop through all the pointers that we detected and 
		// process them as normal touch events.
		for(int i = 0; i < handle.getTouchCount(mo); i++) {
			int tx = (int) handle.getX(mo, i);
			int ty = (int) handle.getY(mo, i);
			
			// Bottom paddle moves when we are playing in one or two player mode and the touch
			// was in the lower quartile of the screen.
			if(mBlue.player && mBlue.inTouchbox(tx,ty)) {
				mBlue.destination = tx;
			}
			else if(mRed.player && mRed.inTouchbox(tx,ty)) {
				mRed.destination = tx;
			}
			else if(mo.getAction() == MotionEvent.ACTION_DOWN && mPauseTouchBox.contains(tx, ty)) {
				if(mCurrentState != State.Stopped) {
					mLastState = mCurrentState;
					mCurrentState = State.Stopped;
				}
				else {
					mCurrentState = mLastState;
					mLastState = State.Stopped;
				}
			}
			
			// In case a player wants to join in...
			if(mo.getAction() == MotionEvent.ACTION_DOWN) {
				if(!mBlue.player && mBlue.inTouchbox(tx,ty)) {
					mBlue.player = true;
				}
				else if(!mRed.player && mRed.inTouchbox(tx,ty)) {
					mRed.player = true;
				}
			}
		}
		
		return true;
	}
	
	@Override
	public boolean onTrackballEvent(MotionEvent event) {
		if(!gameRunning() || mShowTitle) return false;
		
		if(mBlue.player == false) {
			mBlue.player = true;
			mBlue.destination = mBlue.centerX();
		}
		
		switch(event.getAction()) {
		case MotionEvent.ACTION_MOVE:
			mBlue.destination = (int) Math.max(0, Math.min(getWidth(), mBlue.destination + SCROLL_SENSITIVITY * event.getX()));
			break;
		}
		
		return true;
	}
    
	/**
	 * Reset the lives, paddles and the like for a new game.
	 */
	public void newGame() {
		nextRound();
		resumeLastState();
	}
	
	/**
	 * This is kind of useless as well.
	 */
	private void resumeLastState() {
		if(mLastState == State.Stopped && mCurrentState == State.Stopped) {
			mCurrentState = State.Running;
		}
		else if(mCurrentState != State.Stopped) {
			// Do nothing
		}
		else if(mLastState != State.Stopped) {
			mCurrentState = mLastState;
			mLastState = State.Stopped;
		}
	}
	
	public boolean gameRunning() {
		return mShowTitle || (mRed.living() && mBlue.living());
	}
	
	public void setShowTitle(boolean b) {
		mShowTitle = b;
	}
	
	public void pause() {
		if(!mShowTitle) {
			mLastState = mCurrentState;
			mCurrentState = State.Stopped;
		}
	}
	
	public boolean titleShowing() {
		return mShowTitle;
	}


	public boolean onKey(View v, int keyCode, KeyEvent event) {
		return false;
	}

	public void setPlayerControl(boolean red, boolean blue) {
		mRed.player = red;
		mBlue.player = blue;
	}

	public void onCompletion(MediaPlayer mp) {
		mp.seekTo(0);
	}
	
	public void resume() {
		mContinue = true;
		update();
	}
	
	public void stop() {
		mContinue = false;
	}
	
	public void toggleMuted() {
		this.setMuted(!mMuted);
	}
	
	public void setMuted(boolean b) {
		// Set the in-memory flag
		mMuted = b;
		
		// Grab a preference editor
		Context ctx = this.getContext();
		SharedPreferences settings = ctx.getSharedPreferences(Pong.DB_PREFS, 0);
		SharedPreferences.Editor editor = settings.edit();
		
		// Save the value
		editor.putBoolean(Pong.PREF_MUTED, b);
		editor.commit();
		
		// Output a toast to the user
		int rid = (mMuted) ? R.string.sound_disabled : R.string.sound_enabled;
		Toast.makeText(ctx, rid, Toast.LENGTH_SHORT).show();
	}
	
	/**
	 * Put yer resources in year and we'll release em!
	 */
	public void releaseResources() {
		mWallHit.release();
		mPaddleHit.release();
		mWinTone.release();
		mMissTone.release();
	}
	
	private MediaPlayer loadSound(int rid) {
		MediaPlayer mp = MediaPlayer.create(getContext(), rid);
		mp.setOnCompletionListener(this);
		return mp;
	}
	
	private void playSound(MediaPlayer mp) {
		if(mMuted == true) return;
		
		if(!mp.isPlaying()) {
			mp.setVolume(0.2f, 0.2f);
			mp.start();
		}
	}
	
	class Ball {
		public float x, y, xp, yp, vx, vy;
		public float speed = SPEED;
		
		protected double mAngle;
		protected boolean mNextPointKnown = false;
		protected int mCounter = 0;
		
		public Ball() {
			findVector();
		}
		
		public Ball(Ball other) {
			x = other.x;
			y = other.y;
			xp = other.xp;
			yp = other.yp;
			vx = other.vx;
			vy = other.vy;
			speed = other.speed;
			mAngle = other.mAngle;
		}
		
		protected void findVector() {
			vx = (float) (speed * Math.cos(mAngle));
			vy = (float) (speed * Math.sin(mAngle));
		}
		
		public boolean goingUp() {
			return mAngle >= Math.PI;
		}
		
		public boolean goingDown() {
			return !goingUp();
		}
		
		public boolean goingLeft() {
			return mAngle <= 3 * Math.PI / 2 && mAngle > Math.PI / 2;
		}
		
		public boolean goingRight() {
			return !goingLeft();
		}
		
		public double getAngle() {
			return mAngle;
		}
		
		public boolean serving() {
			return mCounter > 0;
		}
		
		public void pause() {
			mCounter = 60;
		}
		
		public void move() {
			if(mCounter <= 0) {
				x = keepX(x + vx); 
				y += vy;
			}
			else {
				mCounter--;
			}
		}
		
		public void randomAngle() {
			setAngle( 2 * Math.PI * RNG.nextDouble() );
		}
		
		public void setAngle(double angle) {
			mAngle = angle % (2 * Math.PI);
			mAngle = boundAngle(mAngle);
			findVector();
		}
		
		public void draw(Canvas canvas) {
	        if((mCounter / 10) % 2 == 1 || mCounter == 0)
	        	canvas.drawCircle(x, y, Ball.RADIUS, mPaint);
		}
		
		/**
		 * Tells us if the ball collides with a rectangle.
		 * @param r, the rectangle
		 * @return true if the ball is colliding, false if not
		 */
		public boolean collides(Paddle p) {
			return p.collides(this); 
		}
		
		/**
		 * Method bounces the ball across a vertical axis. Seriously it's that easy.
		 * Math failed me when figuring this out so I guessed instead.
		 */
		public void bouncePaddle(Paddle p) {
			double angle;
			
			// up-right case
			if(mAngle >= Math.PI) {
				angle = 4 * Math.PI - mAngle;
			}
			// down-left case
			else {
				angle = 2 * Math.PI - mAngle;
			}
			
			angle %= (2 * Math.PI);
			angle = salt(angle, p);
			normalize(p);
			setAngle(angle);
		}

		/**
		 * Bounce the ball off a horizontal axis.
		 */
		public void bounceWall() {
			setAngle(3 * Math.PI - mAngle);
		}
		
		protected double salt(double angle, Paddle paddle) {
			int cx = paddle.centerX();
			double halfWidth = paddle.getWidth() / 2;
			double change = 0.0;
			
			if(goingUp()) change = SALT * ((cx - x) / halfWidth);
			else change = SALT * ((x - cx) / halfWidth);
			
			return boundAngle(angle, change);
		}
		
		/**
		 * Normalizes a ball's position after it has hit a paddle.
		 * @param r The paddle the ball has hit.
		 */
		protected void normalize(Paddle p) {
			// Quit if the ball is outside the width of the paddle
			if(x < p.getLeft() || x > p.getRight()) {
				return;
			}
			
			// Case if ball is above the paddle
			if(y < p.getTop()) {
				y = Math.min(y, p.getTop() - Ball.RADIUS);
			}
			else if(y > p.getBottom()) {
				y = Math.max(y, p.getBottom() + Ball.RADIUS);
			}
		}
		
		/**
		 * Bounds sum of <code>angle</code> and <code>angleChange</code> to the side of the
		 * unit circle that <code>angle</code> is on.
		 * @param angle The initial angle.
		 * @param angleChange Amount to add to angle.
		 * @return bounded angle sum
		 */
		protected double boundAngle(double angle, double angleChange) {
			return boundAngle(angle + angleChange, angle >= Math.PI);
		}
		
		protected double boundAngle(double angle) {
			return boundAngle(angle, angle >= Math.PI);
		}
		
		/**
		 * Bounds an angle in radians to a subset of the top
		 * or bottom part of the unit circle.
		 * @param angle The angle in radians to bound.
		 * @param top Flag which indicates if we should bound to the top or not.
		 * @return the bounded angle
		 */
		protected double boundAngle(double angle, boolean top) {
			if(top) {
				return Math.max(Math.PI + BOUND, Math.min(2 * Math.PI - BOUND, angle));
			}

			return Math.max(BOUND, Math.min(Math.PI - BOUND, angle));
		}
		

		/**
		 * Given it a coordinate, it transforms it into a proper x-coordinate for the ball.
		 * @param x, the x-coord to transform
		 * @return
		 */
		protected float keepX(float x) {
			return bound(x, Ball.RADIUS, getWidth() - Ball.RADIUS);
		}
		
		public static final double BOUND = Math.PI / 9;
		public static final float SPEED = 4.0f; 
		public static final int RADIUS = 4;
		public static final double SALT = 4 * Math.PI / 9;
	}

	class Paddle {
		protected int mColor;
		protected Rect mRect;
		protected Rect mTouch;
		protected int mHandicap = 0;
		protected int mSpeed = 4;
		protected int mLives = 3;
		
		public boolean player = false;

		public int destination;
		
		public Paddle(int c, int y) {
			mColor = c;
			
			int mid = PongView.this.getWidth() / 2;
			mRect = new Rect(mid - PADDLE_WIDTH / 2, y,
					mid + PADDLE_WIDTH / 2, y + PADDLE_THICKNESS);
			destination = mid;
		}
		
		public void move() {
			move(mSpeed);
		}
		
		public void move(boolean handicapped) {
			move((handicapped) ? mSpeed - mHandicap : mSpeed);
		}
		
		public void move(int s) {
			int dx = (int) Math.abs(mRect.centerX() - destination);
			
			if(destination < mRect.centerX()) {
				mRect.offset( (dx > s) ? -s : -dx, 0);
			}
			else if(destination > mRect.centerX()) {
				mRect.offset( (dx > mSpeed) ? mSpeed : dx, 0);
			}
		}
		
		public void setTouchbox(Rect r) {
			mTouch = r;
		}
		
		public void setSpeed(int s) {
			mSpeed = (s > 0) ? s : mSpeed;
		}
		
		public void setHandicap(int h) {
			mHandicap = (h >= 0 && h < mSpeed) ? h : mHandicap; 
		}
		
		public boolean inTouchbox(int x, int y) {
			return mTouch.contains(x, y);
		}
		
		public void loseLife() {
			mLives = Math.max(0, mLives - 1);
		}
		
		public boolean living() {
			return mLives > 0;
		}
		
		public int getWidth() {
			return Paddle.PADDLE_WIDTH;
		}
		
		public int getTop() {
			return mRect.top;
		}
		
		public int getBottom() {
			return mRect.bottom;
		}
		
		public int centerX() {
			return mRect.centerX();
		}
		
		public int centerY() {
			return mRect.centerY();
		}
		
		public int getLeft() {
			return mRect.left;
		}
		
		public int getRight() {
			return mRect.right;
		}
		
		public int touchCenterY() {
			return mTouch.centerY();
		}
		
		public int getLives() {
			return mLives;
		}
		
		public void draw(Canvas canvas) {
			mPaint.setColor(mColor);
			mPaint.setStyle(Style.FILL);
			canvas.drawRect(mRect, mPaint);
		}
		
		public void drawTouchbox(Canvas canvas) {
			mPaint.setColor(mColor);
			mPaint.setStyle(Style.STROKE);
			canvas.drawRect(mTouch, mPaint);
		}
		
		public boolean collides(Ball b) {
			return b.x >= mRect.left && b.x <= mRect.right && 
			b.y >= mRect.top - Ball.RADIUS && b.y <= mRect.bottom + Ball.RADIUS;
		}
		
		/** Thickness of the paddle */
		private static final int PADDLE_THICKNESS = 10;
		
		/** Width of the paddle */
		private static final int PADDLE_WIDTH = 40;
	}
}
