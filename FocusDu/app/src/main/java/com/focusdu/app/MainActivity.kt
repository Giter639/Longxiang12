package com.focusdu.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.appbar.MaterialToolbar

/**
 * X02 规定的唯一 Activity：所有页面都是 Fragment，导航见 res/navigation/nav_graph.xml。
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val navHost = supportFragmentManager.findFragmentById(R.id.nav_host) as NavHostFragment
        findViewById<MaterialToolbar>(R.id.toolbar).setupWithNavController(navHost.navController)
    }
}
