package miao.kmirror.cameraxforpcmonitoring

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import miao.kmirror.cameraxforpcmonitoring.databinding.ActivityMainBinding
import miao.kmirror.library.ui.BaseActivity
import java.net.NetworkInterface

class MainActivity : BaseActivity<ActivityMainBinding>() {
    override fun getViewBinding(layoutInflater: LayoutInflater): ActivityMainBinding {
        return ActivityMainBinding.inflate(layoutInflater)
    }

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding.cardToCamerax.setOnClickListener {
            CameraxActivity.startActivity(this)
        }
        binding.tvIpaddr.text = "WebSocket 地址为：\n" +
                "ws://" + getIpAddressInLocalNetwork() + ":" + BuildConfig.Port + "/live"
    }

    private fun getIpAddressInLocalNetwork(): String? {
        val networkInterfaces = NetworkInterface.getNetworkInterfaces().iterator().asSequence()
        val localAddresses = networkInterfaces.flatMap {
            it.inetAddresses.asSequence()
                .filter { inetAddress ->
                    inetAddress.isSiteLocalAddress && !inetAddress.hostAddress.contains(":") &&
                            inetAddress.hostAddress != "127.0.0.1"
                }
                .map { inetAddress -> inetAddress.hostAddress }
        }
        return localAddresses.firstOrNull()
    }
}