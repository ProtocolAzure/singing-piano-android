# 在电脑上运行：python "导入我的Yamaha音源.py"
# 选择你之前能正常发声的、约 6.5 MB 的 V3.3 HTML。
from pathlib import Path
import json,re,tkinter as tk
from tkinter import filedialog,messagebox
root=tk.Tk();root.withdraw()
path=filedialog.askopenfilename(title="选择现有的真实 Yamaha C5 钢琴 HTML",filetypes=[("HTML 文件","*.html")])
if not path:print("已取消");raise SystemExit
raw=Path(path).read_text(encoding="utf-8")
m=re.search(r'const SAMPLE_DATA\s*=\s*(\{[\s\S]*?\})\s*;\s*const sampleCache',raw)
if not m:messagebox.showerror("导入失败","没有找到真实钢琴采样。请选已经能正常播放的成品 HTML。") ;raise SystemExit(1)
samples=json.loads(m.group(1))
if len(samples)<2:raise ValueError("音源样本不足")
target=Path(__file__).resolve().parent/'app/src/main/assets/index.html'
txt=target.read_text(encoding='utf-8')
if '__SAMPLE_DATA__' not in txt:messagebox.showerror("导入失败","已经导入过音源。请用原始工程重新操作。");raise SystemExit(1)
target.write_text(txt.replace('__SAMPLE_DATA__',json.dumps(samples,ensure_ascii=False,separators=(',',':'))),encoding='utf-8')
messagebox.showinfo("导入成功",f"已经保留 {len(samples)} 段真实钢琴录音！\n接下来用 Android Studio 打开工程并 Build APK。")
print('成功导入',len(samples),'段钢琴采样，目标：',target)
