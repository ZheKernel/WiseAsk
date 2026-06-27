import { useEffect, useMemo, useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import * as z from "zod";
import { toast } from "sonner";

import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import {
  Form,
  FormControl,
  FormDescription,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from "@/components/ui/form";
import { Input } from "@/components/ui/input";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Button } from "@/components/ui/button";

import { createKnowledgeBase } from "@/services/knowledgeService";
import { getRagCapabilities, type RagCapabilities } from "@/services/settingsService";
import { useAuthStore } from "@/stores/authStore";
import { getErrorMessage } from "@/utils/error";

const formSchema = z.object({
  name: z.string().min(1, "请输入知识库名称").max(50, "名称不能超过50个字符"),
  embeddingModel: z.string().min(1, "请选择Embedding模型"),
  collectionName: z
    .string()
    .min(1, "请输入Collection名称")
    .max(50, "名称不能超过50个字符")
    .regex(/^[a-z0-9]+$/, "只能包含小写英文字母和数字"),
  scope: z.enum(["GLOBAL", "PERSONAL"]),
});

type FormValues = z.infer<typeof formSchema>;

interface CreateKnowledgeBaseDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onSuccess: () => void;
}

export function CreateKnowledgeBaseDialog({
  open,
  onOpenChange,
  onSuccess,
}: CreateKnowledgeBaseDialogProps) {
  const [loading, setLoading] = useState(false);
  const [modelLoading, setModelLoading] = useState(false);
  const [embeddingModels, setEmbeddingModels] = useState<RagCapabilities["embeddingModels"]>([]);
  const user = useAuthStore((state) => state.user);
  const isAdmin = user?.role === "admin";

  const form = useForm<FormValues>({
    resolver: zodResolver(formSchema),
    defaultValues: {
      name: "",
      embeddingModel: "",
      collectionName: "",
      scope: isAdmin ? "GLOBAL" : "PERSONAL",
    },
  });

  useEffect(() => {
    if (!open) return;
    let active = true;
    form.setValue("scope", isAdmin ? "GLOBAL" : "PERSONAL");
    setModelLoading(true);
    getRagCapabilities()
      .then((capabilities) => {
        if (!active) return;
        const models = capabilities.embeddingModels || [];
        setEmbeddingModels(models);
        const defaultModel = capabilities.defaultEmbeddingModel;
        if (!form.getValues("embeddingModel")
          && defaultModel
          && models.some((model) => model.id === defaultModel)) {
          form.setValue("embeddingModel", capabilities.defaultEmbeddingModel);
        }
      })
      .catch(() => {
        if (active) {
          setEmbeddingModels([]);
        }
      })
      .finally(() => {
        if (active) {
          setModelLoading(false);
        }
      });
    return () => {
      active = false;
    };
  }, [isAdmin, open, form]);

  const selectOptions = useMemo(() => {
    if (embeddingModels.length === 0) return [];
    const uniqueMap = new Map<string, RagCapabilities["embeddingModels"][number]>();
    embeddingModels.forEach((item) => {
      if (item.id) {
        uniqueMap.set(item.id, item);
      }
    });
    return Array.from(uniqueMap.values());
  }, [embeddingModels]);

  const onSubmit = async (values: FormValues) => {
    try {
      setLoading(true);
      await createKnowledgeBase(values);
      toast.success("创建成功");
      form.reset();
      onOpenChange(false);
      onSuccess();
    } catch (error) {
      toast.error(getErrorMessage(error, "创建失败"));
      console.error(error);
    } finally {
      setLoading(false);
    }
  };

  const handleDialogOpenChange = (nextOpen: boolean) => {
    if (!nextOpen) {
      form.reset({
        name: "",
        embeddingModel: "",
        collectionName: "",
        scope: isAdmin ? "GLOBAL" : "PERSONAL",
      });
    }
    onOpenChange(nextOpen);
  };

  return (
    <Dialog open={open} onOpenChange={handleDialogOpenChange}>
      <DialogContent
        className="sm:max-w-[500px]"
        onOpenAutoFocus={(e) => e.preventDefault()}
      >
        <DialogHeader>
          <DialogTitle>创建知识库</DialogTitle>
          <DialogDescription>
            创建一个新的知识库，用于存储和检索文档
          </DialogDescription>
        </DialogHeader>

        <Form {...form}>
          <form onSubmit={form.handleSubmit(onSubmit)} className="space-y-4">
            <FormField
              control={form.control}
              name="name"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>知识库名称</FormLabel>
                  <FormControl>
                    <Input placeholder="例如：产品文档库" {...field} />
                  </FormControl>
                  <FormDescription>
                    为知识库起一个易于识别的名称
                  </FormDescription>
                  <FormMessage />
                </FormItem>
              )}
            />

            <FormField
              control={form.control}
              name="embeddingModel"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Embedding模型</FormLabel>
                  <Select value={field.value} onValueChange={field.onChange}>
                    <FormControl>
                      <SelectTrigger>
                        <SelectValue placeholder="选择Embedding模型" />
                      </SelectTrigger>
                    </FormControl>
                    <SelectContent>
                      {modelLoading ? (
                        <SelectItem value="loading" disabled>
                          加载中...
                        </SelectItem>
                      ) : selectOptions.length === 0 ? (
                        <SelectItem value="empty" disabled>
                          暂无可用模型
                        </SelectItem>
                      ) : (
                        selectOptions.map((item) => {
                          const label = item.provider && item.model
                            ? `${item.provider} · ${item.model}`
                            : item.model || item.id;
                          return (
                            <SelectItem key={item.id} value={item.id}>
                              {label}
                            </SelectItem>
                          );
                        })
                      )}
                    </SelectContent>
                  </Select>
                  <FormDescription>
                    选择用于向量化文档的模型
                  </FormDescription>
                  <FormMessage />
                </FormItem>
              )}
            />

            <FormField
              control={form.control}
              name="collectionName"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Collection名称</FormLabel>
                  <FormControl>
                    <Input placeholder="例如：productdocs" {...field} />
                  </FormControl>
                  <FormDescription>
                    只能包含小写英文字母和数字
                  </FormDescription>
                  <FormMessage />
                </FormItem>
              )}
            />

            {isAdmin ? (
              <FormField
                control={form.control}
                name="scope"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>可见范围</FormLabel>
                    <Select value={field.value} onValueChange={field.onChange}>
                      <FormControl>
                        <SelectTrigger>
                          <SelectValue />
                        </SelectTrigger>
                      </FormControl>
                      <SelectContent>
                        <SelectItem value="GLOBAL">全局知识库</SelectItem>
                        <SelectItem value="PERSONAL">个人知识库</SelectItem>
                      </SelectContent>
                    </Select>
                    <FormDescription>
                      全局知识库可供所有用户检索，个人知识库仅自己可管理
                    </FormDescription>
                    <FormMessage />
                  </FormItem>
                )}
              />
            ) : null}

            <DialogFooter>
              <Button
                type="button"
                variant="outline"
                onClick={() => handleDialogOpenChange(false)}
                disabled={loading}
              >
                取消
              </Button>
              <Button type="submit" disabled={loading}>
                {loading ? "创建中..." : "创建"}
              </Button>
            </DialogFooter>
          </form>
        </Form>
      </DialogContent>
    </Dialog>
  );
}
