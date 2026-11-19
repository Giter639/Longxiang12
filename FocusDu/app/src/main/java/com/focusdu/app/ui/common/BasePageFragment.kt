package com.focusdu.app.ui.common

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.focusdu.app.R
import com.focusdu.app.databinding.FragmentPageBinding
import com.google.android.material.button.MaterialButton

/**
 * P01–P14 页面骨架基类。
 * 新页面三件事：继承它 → 写 ownerTag/hint → 在 onPageReady 里绑定状态或加按钮。
 * 布局复用 fragment_page.xml；布局确实不同再复制该 xml 改名。
 */
abstract class BasePageFragment : Fragment(R.layout.fragment_page) {

    protected val vm: AppSessionViewModel by activityViewModels {
        AppSessionViewModel.Factory(requireActivity().application)
    }

    protected lateinit var binding: FragmentPageBinding
        private set

    /** 页面归属：负责人 · 任务ID · 验收锚点（开发期显示，上线可隐藏） */
    protected abstract val ownerTag: String

    /** 本页要做什么 / 还缺什么，给接手的人看的TODO说明 */
    protected abstract val hintText: String

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentPageBinding.bind(view)
        binding.pageTitle.text = findNavController().currentDestination?.label ?: ""
        binding.pageOwner.text = ownerTag
        binding.pageHint.text = hintText
        onPageReady(view)
    }

    protected abstract fun onPageReady(view: View)

    protected fun navigateTo(destinationId: Int) {
        findNavController().navigate(destinationId)
    }

    protected fun addButton(label: String, onClick: () -> Unit): MaterialButton {
        val b = MaterialButton(requireContext())
        b.text = label
        binding.pageBody.addView(b)
        b.setOnClickListener { onClick() }
        return b
    }
}
