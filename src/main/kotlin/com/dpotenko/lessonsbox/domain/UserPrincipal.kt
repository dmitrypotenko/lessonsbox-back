package com.dpotenko.lessonsbox.domain

import com.dpotenko.lessonsbox.tables.pojos.User
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.core.user.OAuth2User
import java.io.Serializable

open class UserPrincipal(val id: Long,
                         val username: String?,
                         val password: String?,
                         private val authorities: Collection<GrantedAuthority?>) : OAuth2User,  Serializable {
    private var attributes: Map<String, Any>? = null

    val isAccountNonExpired: Boolean
        get() = true

    val isAccountNonLocked: Boolean
        get() = true

    val isCredentialsNonExpired: Boolean
        get() = true

    val isEnabled: Boolean
        get() = true

    override fun getAuthorities(): Collection<GrantedAuthority?> {
        return authorities
    }

    override fun getAttributes(): Map<String, Any>? {
        return attributes
    }

    fun setAttributes(attributes: Map<String, Any>?) {
        this.attributes = attributes
    }

    override fun getName(): String {
        return id.toString()
    }

    fun isAdmin(): Boolean {
        return authorities.any { it?.authority == "ROLE_ADMIN" }
    }

    fun isSuperAdmin(): Boolean {
        return authorities.any { it?.authority == "ROLE_SUPER_ADMIN" }
    }

    companion object {

        private const val serialVersionUID = 20180617104123L

        fun create(user: User): UserPrincipal {
            val authorities: List<GrantedAuthority> = user.role.split(",").map { SimpleGrantedAuthority(it) }
            return UserPrincipal(
                    user.id,
                    user.email,
                    user.password,
                    authorities
            )
        }

        fun create(user: User,
                   attributes: Map<String, Any>?): UserPrincipal {
            val userPrincipal = create(user)
            userPrincipal.setAttributes(attributes)
            return userPrincipal
        }
    }

}
