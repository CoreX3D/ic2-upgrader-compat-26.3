package dev.core.ic2upgrader;

/** The selected enchantment belongs to a particular open menu, never to a global player cache. */
public interface BookTargetMenu {
    BookTargets.Key ic2upgrader$getBookTarget();
    void ic2upgrader$setBookTarget(BookTargets.Key key);
}
