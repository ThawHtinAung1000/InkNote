package wlv.ac.uk.inknote.ui.welcome;

import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import wlv.ac.uk.inknote.R;
import wlv.ac.uk.inknote.databinding.FragmentWelcomeBinding;

import static org.junit.Assert.assertEquals;

/** Exercises motion cleanup without opening an Activity, preferences, or a database. */
@RunWith(AndroidJUnit4.class)
public final class WelcomeMotionTest {
    private static final float EPSILON = 0.0001f;

    @Test
    public void stopRestoresEveryAnimatedViewToItsCanonicalProperties() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            FragmentWelcomeBinding binding = inflate();
            WelcomeMotion motion = new WelcomeMotion(binding);
            try {
                motion.start(true);
                for (View view : animatedViews(binding)) {
                    // Simulate stopping at an arbitrary point in an entrance or float.
                    view.setAlpha(0.25f);
                    view.setTranslationY(17f);
                    view.setScaleX(0.7f);
                    view.setScaleY(0.8f);
                    view.setRotation(3f);
                    view.setVisibility(View.INVISIBLE);
                }
                motion.stop();
                assertCanonical(binding);
                motion.stop();
                assertCanonical(binding);
            } finally {
                motion.stop();
            }
        });
    }

    @Test
    public void resumeWithoutEntranceKeepsContentVisibleAndAllowsRepeatedStartStop() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            FragmentWelcomeBinding binding = inflate();
            WelcomeMotion motion = new WelcomeMotion(binding);
            try {
                motion.start(false);
                for (View view : animatedViews(binding)) {
                    assertEquals(View.VISIBLE, view.getVisibility());
                    assertEquals(1f, view.getAlpha(), EPSILON);
                }
                motion.start(false);
                motion.stop();
                assertCanonical(binding);
            } finally {
                motion.stop();
            }
        });
    }

    private static FragmentWelcomeBinding inflate() {
        Context context = new ContextThemeWrapper(
                InstrumentationRegistry.getInstrumentation().getTargetContext(), R.style.Theme_InkNote);
        return FragmentWelcomeBinding.inflate(LayoutInflater.from(context));
    }

    private static View[] animatedViews(FragmentWelcomeBinding binding) {
        return new View[] {
                binding.logoMark, binding.brandName, binding.notebookIllustration,
                binding.headline, binding.subhead, binding.benefitOffline,
                binding.benefitPhoto, binding.benefitShare, binding.startWriting,
                binding.skipIntroduction, binding.reassurance
        };
    }

    private static void assertCanonical(FragmentWelcomeBinding binding) {
        for (View view : animatedViews(binding)) {
            assertEquals(View.VISIBLE, view.getVisibility());
            assertEquals(1f, view.getAlpha(), EPSILON);
            assertEquals(0f, view.getTranslationX(), EPSILON);
            assertEquals(0f, view.getTranslationY(), EPSILON);
            assertEquals(1f, view.getScaleX(), EPSILON);
            assertEquals(1f, view.getScaleY(), EPSILON);
            assertEquals(0f, view.getRotation(), EPSILON);
        }
    }
}
