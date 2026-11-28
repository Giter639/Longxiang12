package com.focusdu.app.ui.common

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.focusdu.app.R
import com.focusdu.app.databinding.FragmentPageBinding
import com.google.android.material.button.MaterialButton

/**
 * P01–P14 页面骨架基类（UI 改版后）。
 * 新页面三件事：继承它 → 写 ownerTag/hint → 在 onPageReady 里绑定状态或加内容。
 * 布局复用 fragment_page.xml；布局确实不同再复制该 xml 改名。
 *
 * 设计系统入口（保持全 App 一致，勿在页面里手写颜色/圆角）：
 * - [addCard]：白底描边卡片，返回容器往里堆内容；可带小节标题
 * - [addSectionLabel]：小节灰字标签
 * - [addPrimaryButton]：主行动（实心品牌绿）；[addButton]：次级行动（浅绿 tonal）
 */
abstract class BasePageFragment : Fragment(R.layout.fragment_page) {

    protected val vm: AppSessionViewModel by activityViewModels {
        AppSessionViewModel.Factory(requireActivity().application)
    }

    protected lateinit var binding: FragmentPageBinding
        private set

    /** 页面归属：负责人 · 任务ID · 验收锚点（开发期信息，改版后默认隐藏，见 page_owner） */
    protected abstract val ownerTag: String

    /** 本页要做什么 / 还缺什么（开发期信息，改版后默认隐藏，见 page_hint） */
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

    // ---- 设计系统组件 ----

    /** 白底描边圆角卡片；title 非空时在卡内加粗体小标题。返回卡体容器供页面堆内容 */
    protected fun addCard(title: String? = null, into: LinearLayout = binding.pageBody): LinearLayout {
        val card = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_status_card)
            setPadding(
                resources.getDimensionPixelSize(R.dimen.card_padding),
                resources.getDimensionPixelSize(R.dimen.card_padding),
                resources.getDimensionPixelSize(R.dimen.card_padding),
                resources.getDimensionPixelSize(R.dimen.card_padding),
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) }
        }
        if (!title.isNullOrEmpty()) {
            card.addView(sectionLabel(title, parentCard = true))
        }
        into.addView(card)
        return card
    }

    /** 小节灰字标签；parentCard=true 时样式更紧凑（卡内标题用墨色粗体） */
    protected fun sectionLabel(text: String, parentCard: Boolean = false): TextView {        val tv = TextView(requireContext())
        tv.text = text
        tv.textSize = if (parentCard) 15f else 12f
        tv.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (parentCard) R.color.focusdu_ink else R.color.focusdu_text_hint,
            ),
        )
        tv.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(if (parentCard) 8 else 4) }
        return tv
    }

    /** 主行动按钮：实心品牌绿、圆角、占满宽度 */
    protected fun addPrimaryButton(label: String, onClick: () -> Unit): MaterialButton {
        val b = styledButton(label, filled = true)
        b.setOnClickListener { onClick() }
        binding.pageBody.addView(b)
        return b
    }

    /** 次级行动按钮：品牌绿浅底 tonal、圆角（旧 addButton 的改版样式） */
    protected fun addButton(label: String, onClick: () -> Unit): MaterialButton {
        val b = styledButton(label, filled = false)
        b.setOnClickListener { onClick() }
        binding.pageBody.addView(b)
        return b
    }

    /** 往指定容器加次级按钮（用于并排按钮行等场景） */
    protected fun addButtonInto(parent: LinearLayout, label: String, onClick: () -> Unit): MaterialButton {
        val b = styledButton(label, filled = false)
        b.setOnClickListener { onClick() }
        parent.addView(b)
        return b
    }

    /** 小节灰字标签（追加到页面主容器） */
    protected fun addSectionLabel(text: String): TextView {
        val tv = sectionLabel(text)
        binding.pageBody.addView(tv)
        return tv
    }

    private fun styledButton(label: String, filled: Boolean): MaterialButton {
        val b = MaterialButton(requireContext())
        b.text = label
        b.cornerRadius = resources.getDimensionPixelSize(R.dimen.button_radius)
        if (filled) {
            b.backgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.focusdu_primary)
            b.setTextColor(ContextCompat.getColor(requireContext(), R.color.focusdu_on_primary))
        } else {
            b.backgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.focusdu_primary_tint)
            b.setTextColor(ContextCompat.getColor(requireContext(), R.color.focusdu_primary_dark))
        }
        b.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8) }
        return b
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 横向按钮行（两个按钮并排等宽，首页/番茄页用） */
    protected fun addButtonRow(into: LinearLayout = binding.pageBody): LinearLayout {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(4) }
        }
        into.addView(row)
        return row
    }

    /** 往行里加一个等宽次级按钮 */
    protected fun addRowButton(row: LinearLayout, label: String, onClick: () -> Unit): MaterialButton {
        val b = styledButton(label, filled = false).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            }
        }
        b.setOnClickListener { onClick() }
        row.addView(b)
        return b
    }
}
