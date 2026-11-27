package org.thoughtcrime.securesms.components

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.View.OnLongClickListener
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.appcompat.widget.AppCompatImageButton
import org.signal.core.ui.util.ViewUtil
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.conversation.MessageSendType

/**
 * The send button you see in a conversation.
 * Also encapsulates the long-press menu that allows users to switch [MessageSendType]s.
 */
class SendButton(context: Context, attributeSet: AttributeSet?) : AppCompatImageButton(context, attributeSet), OnLongClickListener {

  private var scheduledSendListener: ScheduledSendListener? = null
  private var sendTypeListener: SendTypeChangedListener? = null

  private var popupContainer: ViewGroup? = null

  // Fork: the transports (Signal, SMS/MMS per SIM) this conversation can use.
  private var availableSendTypes: List<MessageSendType> = listOf(MessageSendType.SignalMessageSendType)

  var selectedSendType: MessageSendType = MessageSendType.SignalMessageSendType
    private set

  init {
    setOnLongClickListener(this)
    ViewUtil.mirrorIfRtl(this, getContext())
    applySendType(MessageSendType.SignalMessageSendType)
  }

  fun setScheduledSendListener(listener: ScheduledSendListener?) {
    this.scheduledSendListener = listener
  }

  fun setSendTypeChangedListener(listener: SendTypeChangedListener?) {
    this.sendTypeListener = listener
  }

  /**
   * Must be called with a view that is acceptable for determining the bounds of the popup selector.
   */
  fun setPopupContainer(container: ViewGroup) {
    popupContainer = container
  }

  /**
   * Fork: sets the send types this conversation offers. Keeps the current selection if it is still
   * available, otherwise switches to [defaultType].
   */
  fun setAvailableSendTypes(types: List<MessageSendType>, defaultType: MessageSendType) {
    availableSendTypes = types.ifEmpty { listOf(MessageSendType.SignalMessageSendType) }
    val next = if (availableSendTypes.contains(selectedSendType)) selectedSendType else defaultType
    if (next != selectedSendType || types.size == 1) {
      select(next)
    }
  }

  private fun select(type: MessageSendType) {
    val changed = type != selectedSendType
    applySendType(type)
    if (changed) {
      sendTypeListener?.onSendTypeChanged(type)
    }
  }

  private fun applySendType(type: MessageSendType) {
    selectedSendType = type
    setImageResource(type.buttonDrawableRes)
    contentDescription = type.getTitle(context)
  }

  override fun onLongClick(v: View): Boolean {
    if (!isEnabled) {
      return false
    }

    if (availableSendTypes.size <= 1) {
      if (selectedSendType.usesSignalTransport) {
        scheduledSendListener?.onSendScheduled()
      }
      return true
    }

    val popup = PopupMenu(context, this)
    availableSendTypes.forEachIndexed { index, type ->
      popup.menu.add(0, index, index, type.getTitle(context)).apply {
        isCheckable = true
        isChecked = type == selectedSendType
      }
    }

    val scheduleId = availableSendTypes.size
    if (selectedSendType.usesSignalTransport && scheduledSendListener != null) {
      popup.menu.add(1, scheduleId, scheduleId, R.string.ExternalMessageSendType__schedule_message)
    }

    popup.setOnMenuItemClickListener { item ->
      if (item.itemId == scheduleId) {
        scheduledSendListener?.onSendScheduled()
      } else {
        availableSendTypes.getOrNull(item.itemId)?.let { select(it) }
      }
      true
    }
    popup.show()
    return true
  }

  interface ScheduledSendListener {
    fun onSendScheduled()
  }

  /** Fork: notified when the user picks a different transport from the long-press menu. */
  fun interface SendTypeChangedListener {
    fun onSendTypeChanged(sendType: MessageSendType)
  }
}
