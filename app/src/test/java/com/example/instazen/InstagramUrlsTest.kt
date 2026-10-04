package com.example.instazen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstagramUrlsTest {

    @Test
    fun instagramHostsIncludeWwwMobileAndBare() {
        assertTrue(InstagramUrls.isInstagramHost("www.instagram.com"))
        assertTrue(InstagramUrls.isInstagramHost("instagram.com"))
        assertTrue(InstagramUrls.isInstagramHost("m.instagram.com"))
        assertTrue(InstagramUrls.isInstagramHost("WWW.INSTAGRAM.COM"))
    }

    @Test
    fun nonInstagramHostsAreRejected() {
        assertFalse(InstagramUrls.isInstagramHost("notinstagram.com"))
        assertFalse(InstagramUrls.isInstagramHost("instagram.com.evil.example"))
        assertFalse(InstagramUrls.isInstagramHost(""))
    }

    @Test
    fun directAndAuthPathsMatchInstagramFlows() {
        assertTrue(InstagramUrls.isDirectPath("/direct/inbox/"))
        assertTrue(InstagramUrls.isDirectPath("/direct/t/123"))
        assertFalse(InstagramUrls.isDirectPath("/"))
        assertFalse(InstagramUrls.isDirectPath("/accounts/login/"))

        assertTrue(InstagramUrls.isAuthPath("/accounts/login/"))
        assertTrue(InstagramUrls.isAuthPath("/challenge/"))
        assertTrue(InstagramUrls.isAuthPath("/consent/"))
        assertTrue(InstagramUrls.isAuthPath("/two_factor/"))
        assertFalse(InstagramUrls.isAuthPath("/direct/inbox/"))
        assertFalse(InstagramUrls.isAuthPath("/"))
    }

    @Test
    fun facebookLoginHostsAreRecognized() {
        assertTrue(InstagramUrls.isMetaLoginHost("www.facebook.com"))
        assertTrue(InstagramUrls.isMetaLoginHost("m.facebook.com"))
        assertTrue(InstagramUrls.isMetaLoginHost("facebook.com"))
        assertFalse(InstagramUrls.isMetaLoginHost("instagram.com"))
    }
}
