package wlv.ac.uk.inknote.activities;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import wlv.ac.uk.inknote.databinding.ActivityMainBinding;

public final class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        ActivityMainBinding binding = ActivityMainBinding.inflate(getLayoutInflater());

        setContentView(binding.getRoot());

        ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (view, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime());

            view.setPadding(insets.left, insets.top, insets.right, insets.bottom);

            return windowInsets;
        });

        ViewCompat.requestApplyInsets(binding.getRoot());
    }
}