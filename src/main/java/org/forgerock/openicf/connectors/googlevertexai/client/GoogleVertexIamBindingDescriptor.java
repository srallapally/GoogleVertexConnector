package org.forgerock.openicf.connectors.googlevertexai.client;

/**
 * Describes a single IAM binding on a Dialogflow CX agent resource.
 *
 * Synthesized from the getIamPolicy response: each (role, member) pair
 * becomes one descriptor.
 */
public class GoogleVertexIamBindingDescriptor {

    private final String id;                // synthetic: agentName:role:member
    private final String agentResourceName;
    private final String role;              // e.g. roles/dialogflow.admin
    private final String member;            // e.g. user:alice@example.com
    private final String memberType;        // USER, GROUP, SERVICE_ACCOUNT, DOMAIN

    public GoogleVertexIamBindingDescriptor(String id,
                                      String agentResourceName,
                                      String role,
                                      String member,
                                      String memberType) {
        this.id = id;
        this.agentResourceName = agentResourceName;
        this.role = role;
        this.member = member;
        this.memberType = memberType;
    }

    public String getId() {
        return id;
    }

    public String getAgentResourceName() {
        return agentResourceName;
    }

    public String getRole() {
        return role;
    }

    public String getMember() {
        return member;
    }

    public String getMemberType() {
        return memberType;
    }

    /**
     * Maps member type to the kind attribute used in the schema.
     */
    public String getKind() {
        if (memberType == null) {
            return "DIRECT";
        }
        switch (memberType) {
            case "GROUP":
                return "GROUP";
            case "SERVICE_ACCOUNT":
                return "SERVICE_ACCOUNT";
            case "DOMAIN":
                return "DOMAIN";
            case "USER":
            default:
                return "DIRECT";
        }
    }

    /**
     * Derive memberType from the member string prefix.
     * e.g. "user:alice@..." → USER, "group:..." → GROUP
     */
    public static String deriveMemberType(String member) {
        if (member == null) {
            return "USER";
        }
        if (member.startsWith("group:")) {
            return "GROUP";
        }
        if (member.startsWith("serviceAccount:")) {
            return "SERVICE_ACCOUNT";
        }
        if (member.startsWith("domain:")) {
            return "DOMAIN";
        }
        return "USER";
    }
}