package io.agora.ktvdemo.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.viewbinding.ViewBinding

abstract class BaseFragment<T : ViewBinding?> : Fragment() {

    protected val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private var rootView: View? = null
    var binding: T? = null
        private set

    protected abstract fun getViewBinding(inflater: LayoutInflater, container: ViewGroup?): T

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        if (rootView == null) {
            binding = getViewBinding(inflater, container)
            rootView = binding!!.root
        }
        return rootView
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
        rootView = null
    }

    protected fun toast(msg:String) {
        Toast.makeText(requireContext(),msg, Toast.LENGTH_LONG).show()
    }
}