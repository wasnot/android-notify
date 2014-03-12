package com.example.app;

import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v4.app.Fragment;
import android.support.v7.app.ActionBarActivity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

public class MainActivity extends ActionBarActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .add(R.id.container, new PlaceholderFragment())
                    .commit();
        }
    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {

        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();
        if (id == R.id.action_settings) {
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * A placeholder fragment containing a simple view.
     */
    public static class PlaceholderFragment extends Fragment implements View.OnClickListener,
            SharedPreferences.OnSharedPreferenceChangeListener {

        TextView text;

        public PlaceholderFragment() {
        }

        @Override
        public View onCreateView(LayoutInflater inflater, ViewGroup container,
                                 Bundle savedInstanceState) {
            View rootView = inflater.inflate(R.layout.fragment_main, container, false);
            Button startButton = (Button) rootView.findViewById(R.id.button);
            startButton.setOnClickListener(this);
            Button pongButton = (Button) rootView.findViewById(R.id.button2);
            pongButton.setOnClickListener(this);
            text = (TextView) rootView.findViewById(R.id.textView);
            return rootView;
        }

        @Override
        public void onResume() {
            super.onResume();
            if (getActivity() == null)
                return;
            PreferenceManager.getDefaultSharedPreferences(getActivity()).registerOnSharedPreferenceChangeListener(this);
        }

        @Override
        public void onPause() {
            super.onPause();
            if (getActivity() == null)
                return;
            PreferenceManager.getDefaultSharedPreferences(getActivity()).unregisterOnSharedPreferenceChangeListener(this);
        }

        @Override
        public void onClick(View v) {
            if (getActivity() == null || !isAdded())
                return;
            switch (v.getId()) {
                case R.id.button:
                    // 一度つなぐとつなぎっぱなしになろうとする, stopは未実装
                    NotifyService.startService(getActivity().getApplicationContext(), Build.MODEL);
                    break;
                case R.id.button2:
                    NotifyService.manualPing(getActivity().getApplicationContext(), Build.MODEL);
                    break;
            }
        }

        @Override
        public void onSharedPreferenceChanged(SharedPreferences pref, String key) {
            if (key.equals(PreferenceKeyValue.SHARED_TEXT)) {
                text.setText(pref.getString(key, "non"));
            }
        }
    }

}
