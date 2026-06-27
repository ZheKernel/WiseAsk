import { Navigate, createBrowserRouter } from "react-router-dom";

import { LoginPage } from "@/pages/LoginPage";
import { ChatPage } from "@/pages/ChatPage";
import { NotFoundPage } from "@/pages/NotFoundPage";
import { AdminLayout } from "@/pages/admin/AdminLayout";
import { DashboardPage } from "@/pages/admin/dashboard/DashboardPage";
import { KnowledgeListPage } from "@/pages/admin/knowledge/KnowledgeListPage";
import { KnowledgeDocumentsPage } from "@/pages/admin/knowledge/KnowledgeDocumentsPage";
import { KnowledgeChunksPage } from "@/pages/admin/knowledge/KnowledgeChunksPage";
import { IntentTreePage } from "@/pages/admin/intent-tree/IntentTreePage";
import { IntentListPage } from "@/pages/admin/intent-tree/IntentListPage";
import { IntentEditPage } from "@/pages/admin/intent-tree/IntentEditPage";
import { IngestionPage } from "@/pages/admin/ingestion/IngestionPage";
import { RagTracePage } from "@/pages/admin/traces/RagTracePage";
import { RagTraceDetailPage } from "@/pages/admin/traces/RagTraceDetailPage";
import { SystemSettingsPage } from "@/pages/admin/settings/SystemSettingsPage";
import { SampleQuestionPage } from "@/pages/admin/sample-questions/SampleQuestionPage";
import { QueryTermMappingPage } from "@/pages/admin/query-term-mapping/QueryTermMappingPage";
import { UserListPage } from "@/pages/admin/users/UserListPage";
import { useAuthStore } from "@/stores/authStore";

function RequireAuth({ children }: { children: JSX.Element }) {
  const isAuthenticated = useAuthStore((state) => state.isAuthenticated);
  if (!isAuthenticated) {
    return <Navigate to="/login" replace />;
  }
  return children;
}

function RequireAdmin({ children }: { children: JSX.Element }) {
  const user = useAuthStore((state) => state.user);
  const isAuthenticated = useAuthStore((state) => state.isAuthenticated);

  if (!isAuthenticated) {
    return <Navigate to="/login" replace />;
  }

  if (user?.role !== "admin") {
    return <Navigate to="/admin/knowledge" replace />;
  }

  return children;
}

function AdminHomeRedirect() {
  const user = useAuthStore((state) => state.user);
  return <Navigate to={user?.role === "admin" ? "/admin/dashboard" : "/admin/knowledge"} replace />;
}

function RedirectIfAuth({ children }: { children: JSX.Element }) {
  const isAuthenticated = useAuthStore((state) => state.isAuthenticated);
  if (isAuthenticated) {
    return <Navigate to="/chat" replace />;
  }
  return children;
}

function HomeRedirect() {
  const isAuthenticated = useAuthStore((state) => state.isAuthenticated);
  return <Navigate to={isAuthenticated ? "/chat" : "/login"} replace />;
}

export const router = createBrowserRouter([
  {
    path: "/",
    element: <HomeRedirect />
  },
  {
    path: "/login",
    element: (
      <RedirectIfAuth>
        <LoginPage />
      </RedirectIfAuth>
    )
  },
  {
    path: "/chat",
    element: (
      <RequireAuth>
        <ChatPage />
      </RequireAuth>
    )
  },
  {
    path: "/chat/:sessionId",
    element: (
      <RequireAuth>
        <ChatPage />
      </RequireAuth>
    )
  },
  {
    path: "/admin",
    element: (
      <RequireAuth>
        <AdminLayout />
      </RequireAuth>
    ),
    children: [
      {
        index: true,
        element: <AdminHomeRedirect />
      },
      {
        path: "dashboard",
        element: (
          <RequireAdmin>
            <DashboardPage />
          </RequireAdmin>
        )
      },
      {
        path: "knowledge",
        element: <KnowledgeListPage />
      },
      {
        path: "knowledge/:kbId",
        element: <KnowledgeDocumentsPage />
      },
      {
        path: "knowledge/:kbId/docs/:docId",
        element: <KnowledgeChunksPage />
      },
      {
        path: "intent-tree",
        element: (
          <RequireAdmin>
            <IntentTreePage />
          </RequireAdmin>
        )
      },
      {
        path: "intent-list",
        element: (
          <RequireAdmin>
            <IntentListPage />
          </RequireAdmin>
        )
      },
      {
        path: "intent-list/:id/edit",
        element: (
          <RequireAdmin>
            <IntentEditPage />
          </RequireAdmin>
        )
      },
      {
        path: "ingestion",
        element: (
          <RequireAdmin>
            <IngestionPage />
          </RequireAdmin>
        )
      },
      {
        path: "traces",
        element: (
          <RequireAdmin>
            <RagTracePage />
          </RequireAdmin>
        )
      },
      {
        path: "traces/:traceId",
        element: (
          <RequireAdmin>
            <RagTraceDetailPage />
          </RequireAdmin>
        )
      },
      {
        path: "settings",
        element: (
          <RequireAdmin>
            <SystemSettingsPage />
          </RequireAdmin>
        )
      },
      {
        path: "sample-questions",
        element: (
          <RequireAdmin>
            <SampleQuestionPage />
          </RequireAdmin>
        )
      },
      {
        path: "mappings",
        element: (
          <RequireAdmin>
            <QueryTermMappingPage />
          </RequireAdmin>
        )
      },
      {
        path: "users",
        element: (
          <RequireAdmin>
            <UserListPage />
          </RequireAdmin>
        )
      }
    ]
  },
  {
    path: "*",
    element: <NotFoundPage />
  }
]);
