package wlv.ac.uk.inknote.ui.welcome;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

import androidx.annotation.MainThread;

import java.util.ArrayList;
import java.util.List;

import wlv.ac.uk.inknote.databinding.FragmentWelcomeBinding;

/** View-lifecycle-scoped welcome motion. No database work or persistent animation callbacks. */
final class WelcomeMotion {
    private final View[] entranceViews;
    private final View scroll;
    private final View logo;
    private final View notebook;
    private final float density;
    private final AccessibilityManager accessibilityManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AccessibilityManager.TouchExplorationStateChangeListener explorationListener =
            enabled -> {
                if (enabled) stop();
            };

    private AnimatorSet running;
    private boolean active;
    private boolean listenerRegistered;
    private boolean entranceStarted;

    @MainThread
    WelcomeMotion(FragmentWelcomeBinding binding) {
        scroll = binding.welcomeScroll;
        logo = binding.logoMark;
        notebook = binding.notebookIllustration;
        entranceViews = new View[]{
                logo, binding.brandName, notebook, binding.headline, binding.subhead,
                binding.benefitOffline, binding.benefitPhoto, binding.benefitShare,
                binding.startWriting, binding.skipIntroduction, binding.reassurance
        };
        density = scroll.getResources().getDisplayMetrics().density;
        accessibilityManager = scroll.getContext().getApplicationContext()
                .getSystemService(AccessibilityManager.class);
    }

    @MainThread
    void start(boolean playEntrance) {
        stop();
        boolean showEntrance = playEntrance && !entranceStarted;
        entranceStarted = true;
        // Recheck every resume: animator scale and touch exploration can change in Settings.
        if (!motionAllowed()) return;
        active = true;
        if (accessibilityManager != null) {
            accessibilityManager.addTouchExplorationStateChangeListener(explorationListener, mainHandler);
            listenerRegistered = true;
        }
        if (showEntrance) startEntrance();
        else startFloat();
    }

    private boolean motionAllowed() {
        return ValueAnimator.areAnimatorsEnabled()
                && (accessibilityManager == null || !accessibilityManager.isTouchExplorationEnabled());
    }

    private void startEntrance() {
        List<Animator> rows = new ArrayList<>();
        for (int index = 0; index < entranceViews.length; index++) {
            View view = entranceViews[index];
            view.setAlpha(0f);
            view.setTranslationY(18f * density);
            List<Animator> properties = new ArrayList<>();
            properties.add(property(view, View.ALPHA, 420, 0f, 1f));
            properties.add(property(view, View.TRANSLATION_Y, 480, 18f * density, 0f));
            if (view == logo) {
                view.setScaleX(0.82f);
                view.setScaleY(0.82f);
                ObjectAnimator scaleX = property(view, View.SCALE_X, 600, 0.82f, 1f);
                ObjectAnimator scaleY = property(view, View.SCALE_Y, 600, 0.82f, 1f);
                scaleX.setInterpolator(new OvershootInterpolator(1.15f));
                scaleY.setInterpolator(new OvershootInterpolator(1.15f));
                properties.add(scaleX);
                properties.add(scaleY);
            } else if (view == notebook) {
                view.setScaleX(0.94f);
                view.setScaleY(0.94f);
                view.setRotation(-3f);
                properties.add(property(view, View.SCALE_X, 540, 0.94f, 1f));
                properties.add(property(view, View.SCALE_Y, 540, 0.94f, 1f));
                properties.add(property(view, View.ROTATION, 600, -3f, 0f));
            }
            AnimatorSet row = new AnimatorSet();
            row.playTogether(properties);
            row.setStartDelay(index * 55L);
            rows.add(row);
        }
        AnimatorSet entrance = new AnimatorSet();
        entrance.playTogether(rows);
        running = entrance;
        entrance.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (running != animation) return;
                running = null;
                resetViews();
                if (active && motionAllowed()) startFloat();
            }
        });
        entrance.start();
    }

    private static ObjectAnimator property(View view, android.util.Property<View, Float> property,
                                           long duration, float... values) {
        ObjectAnimator animator = ObjectAnimator.ofFloat(view, property, values);
        animator.setDuration(duration);
        animator.setInterpolator(new DecelerateInterpolator(1.5f));
        return animator;
    }

    private void startFloat() {
        if (!active || !motionAllowed()) return;
        ObjectAnimator lift = ObjectAnimator.ofFloat(notebook, View.TRANSLATION_Y, 0f, -4f * density, 0f);
        ObjectAnimator tilt = ObjectAnimator.ofFloat(notebook, View.ROTATION, 0f, 0.7f, 0f);
        for (ObjectAnimator animator : new ObjectAnimator[]{lift, tilt}) {
            animator.setDuration(2800);
            animator.setRepeatCount(2); // Three complete out-and-back cycles, then remain still.
            animator.setInterpolator(new AccelerateDecelerateInterpolator());
        }
        AnimatorSet floating = new AnimatorSet();
        floating.playTogether(lift, tilt);
        running = floating;
        floating.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (running != animation) return;
                running = null;
                resetViews();
            }
        });
        floating.start();
    }

    @MainThread
    void stop() {
        active = false;
        if (listenerRegistered && accessibilityManager != null) {
            accessibilityManager.removeTouchExplorationStateChangeListener(explorationListener);
        }
        listenerRegistered = false;
        AnimatorSet animation = running;
        running = null;
        if (animation != null) {
            animation.removeAllListeners();
            animation.cancel();
        }
        scroll.animate().cancel();
        resetViews();
    }

    private void resetViews() {
        for (View view : entranceViews) {
            view.animate().cancel();
            view.setAlpha(1f);
            view.setTranslationY(0f);
            view.setScaleX(1f);
            view.setScaleY(1f);
            view.setRotation(0f);
            view.setVisibility(View.VISIBLE);
        }
    }
}
