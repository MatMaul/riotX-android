/*
 * Copyright 2020 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package im.vector.riotx.features.attachments.preview

import com.airbnb.mvrx.FragmentViewModelContext
import com.airbnb.mvrx.MvRxViewModelFactory
import com.airbnb.mvrx.ViewModelContext
import com.squareup.inject.assisted.Assisted
import com.squareup.inject.assisted.AssistedInject
import im.vector.riotx.core.extensions.exhaustive
import im.vector.riotx.core.platform.VectorViewModel

class AttachmentsPreviewViewModel @AssistedInject constructor(@Assisted initialState: AttachmentsPreviewViewState)
    : VectorViewModel<AttachmentsPreviewViewState, AttachmentsPreviewAction, AttachmentsPreviewViewEvents>(initialState) {

    @AssistedInject.Factory
    interface Factory {
        fun create(initialState: AttachmentsPreviewViewState): AttachmentsPreviewViewModel
    }

    companion object : MvRxViewModelFactory<AttachmentsPreviewViewModel, AttachmentsPreviewViewState> {

        @JvmStatic
        override fun create(viewModelContext: ViewModelContext, state: AttachmentsPreviewViewState): AttachmentsPreviewViewModel? {
            val fragment: AttachmentsPreviewFragment = (viewModelContext as FragmentViewModelContext).fragment()
            return fragment.viewModelFactory.create(state)
        }
    }

    override fun handle(action: AttachmentsPreviewAction) {
        when (action) {
            is AttachmentsPreviewAction.SetCurrentAttachment          -> handleSetCurrentAttachment(action)
            is AttachmentsPreviewAction.UpdatePathOfCurrentAttachment -> handleUpdatePathOfCurrentAttachment(action)
            AttachmentsPreviewAction.RemoveCurrentAttachment          -> handleRemoveCurrentAttachment()
        }.exhaustive
    }

    private fun handleRemoveCurrentAttachment() = withState {
        val currentAttachment = it.attachments.getOrNull(it.currentAttachmentIndex) ?: return@withState
        val attachments = it.attachments.minusElement(currentAttachment)
        val newAttachmentIndex = it.currentAttachmentIndex.coerceAtMost(attachments.size - 1)
        setState {
            copy(attachments = attachments, currentAttachmentIndex = newAttachmentIndex)
        }
    }

    private fun handleUpdatePathOfCurrentAttachment(action: AttachmentsPreviewAction.UpdatePathOfCurrentAttachment) = withState {
        val attachments = it.attachments.mapIndexed { index, contentAttachmentData ->
            if (index == it.currentAttachmentIndex) {
                contentAttachmentData.copy(path = action.newPath)
            } else {
                contentAttachmentData
            }
        }
        setState {
            copy(attachments = attachments)
        }
    }

    private fun handleSetCurrentAttachment(action: AttachmentsPreviewAction.SetCurrentAttachment) = setState {
        copy(currentAttachmentIndex = action.index)
    }
}
