package io.jenkins.plugins.xcpng.client;

/**
 * The strings this plugin stamps on every clone it provisions, so an out-of-band sweep can find the VMs it
 * leaks. Backend-neutral on purpose: each backend writes them in its own shape ({@code other_config} keys on
 * XAPI, tags on Xen Orchestra, see {@link XoRestClient#OWNER_TAG_PREFIX}), and both shapes are built from the
 * two constants here so they stay recognisable as the same thing. They used to live on {@link XapiClient},
 * which meant the Xen Orchestra backend depended on the class it is replacing (#89).
 *
 * <p>{@code tools/owner.py} carries its own copy of these strings, and
 * {@code tools/tests/test_owner.py} reads them out of this file to check the two agree. Change one here and
 * the reaper stops finding VMs the plugin leaks unless the Python moves with it.
 */
public final class OwnerMarker {

    /**
     * Key stamped on every clone this plugin provisions, holding the owning cloud's name. The recovery
     * contract with {@code tools/reaper.py}, which selects on this key rather than on a name prefix: names
     * drift (they already did, silently, and the reaper matched nothing for it), whereas a golden image, an
     * operator's VM and a snapshot cannot acquire this key by being named unluckily.
     *
     * <p><b>The marker says the plugin made this VM <em>or something in its ancestry</em>.</b> {@code VM.clone}
     * copies {@code other_config} and {@code tags}, so a VM an operator hand-clones off a marked agent inherits
     * this marker and reads as plugin-owned to anything selecting on it alone. {@link #SELF_KEY} is what
     * separates the two.
     *
     * <p>Change this string and the reaper stops finding VMs the plugin leaks. Both sides must move together.
     */
    public static final String OWNER_KEY = "xcpng-cloud";

    /**
     * Key holding the uuid of the VM the record belongs to, stamped beside {@link #OWNER_KEY} on every clone
     * this plugin provisions.
     *
     * <p>It exists because the owner marker is inheritable and this is not. {@code VM.clone} copies the
     * marker verbatim, so a hand-made clone of a marked agent carries the owner marker and <em>its
     * source's</em> uuid, while a genuine plugin clone carries its own. A tool comparing the stamped uuid
     * against the record's own therefore selects the clones this plugin made and refuses the copies of them,
     * which the marker alone cannot do (#246).
     *
     * <p>A clone stamped by a version that predates this key carries no uuid at all. Tools treat that as
     * ownership rather than refusing it: those VMs are real leaks and refusing them would strand exactly the
     * disks the reaper exists to reclaim. The hazard survives for them, and only for them.
     */
    public static final String SELF_KEY = OWNER_KEY + "-uuid";

    private OwnerMarker() {}
}
