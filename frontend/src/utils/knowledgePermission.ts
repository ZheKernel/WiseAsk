import type { KnowledgeBase } from "@/services/knowledgeService";
import type { User } from "@/types";

export function isPersonalKnowledgeBase(knowledgeBase?: KnowledgeBase | null) {
  return knowledgeBase?.scope?.toUpperCase() === "PERSONAL";
}

export function canManageKnowledgeBase(
  knowledgeBase?: KnowledgeBase | null,
  user?: User | null
) {
  if (!knowledgeBase || !user) {
    return false;
  }
  if (user.role === "admin") {
    return true;
  }
  return isPersonalKnowledgeBase(knowledgeBase)
    && knowledgeBase.ownerUserId === user.userId;
}
