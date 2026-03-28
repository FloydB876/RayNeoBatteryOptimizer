package com.korvanta.rayneobattery.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.korvanta.rayneobattery.R
import com.korvanta.rayneobattery.databinding.ItemProfileBinding
import com.korvanta.rayneobattery.profile.PowerProfileManager.Profile

class ProfileAdapter(
    private val onProfileSelected: (Profile) -> Unit
) : RecyclerView.Adapter<ProfileAdapter.ViewHolder>() {

    private val profiles = Profile.values().toList()

    var activeProfile: Profile = Profile.BALANCED
        set(value) {
            val oldIdx = profiles.indexOf(field)
            field = value
            val newIdx = profiles.indexOf(value)
            if (oldIdx >= 0) notifyItemChanged(oldIdx)
            if (newIdx >= 0) notifyItemChanged(newIdx)
        }

    // Current focused position — managed by RecyclerViewFocusTracker
    var focusedPosition: Int = -1
        set(value) {
            val old = field
            field = value
            if (old in profiles.indices) notifyItemChanged(old)
            if (field in profiles.indices) notifyItemChanged(field)
        }

    fun getProfileAt(position: Int): Profile? =
        profiles.getOrNull(position)

    fun getCurrentData(): Profile? =
        profiles.getOrNull(focusedPosition)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemProfileBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val profile = profiles[position]
        holder.bind(profile, profile == activeProfile, position == focusedPosition)
    }

    override fun getItemCount() = profiles.size

    inner class ViewHolder(
        private val binding: ItemProfileBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(profile: Profile, isActive: Boolean, isFocused: Boolean) {
            binding.tvProfileName.text = profile.displayName
            binding.tvProfileDesc.text = profile.description

            val colorRes = when (profile) {
                Profile.PERFORMANCE -> R.color.profile_performance
                Profile.BALANCED -> R.color.profile_balanced
                Profile.POWER_SAVER -> R.color.profile_saver
                Profile.ULTRA_SAVER -> R.color.profile_ultra
                Profile.NAVIGATION -> R.color.profile_nav
                Profile.RECORDING -> R.color.profile_record
            }
            binding.viewProfileColor.setBackgroundResource(colorRes)

            binding.tvActive.visibility = if (isActive) View.VISIBLE else View.GONE
            binding.root.isSelected = isFocused

            if (isFocused) {
                binding.root.setBackgroundResource(R.drawable.bg_card_focused)
                // Bright text when focused so selection is unmistakable
                binding.tvProfileName.setTextColor(
                    binding.root.context.resources.getColor(R.color.focus_ring, binding.root.context.theme)
                )
                binding.tvProfileDesc.setTextColor(
                    binding.root.context.resources.getColor(R.color.text_primary, binding.root.context.theme)
                )
            } else {
                binding.root.setBackgroundResource(R.drawable.bg_card)
                binding.tvProfileName.setTextColor(
                    binding.root.context.resources.getColor(R.color.text_primary, binding.root.context.theme)
                )
                binding.tvProfileDesc.setTextColor(
                    binding.root.context.resources.getColor(R.color.text_secondary, binding.root.context.theme)
                )
            }
        }
    }
}
