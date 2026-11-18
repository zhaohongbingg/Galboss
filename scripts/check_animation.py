import json
import sys

def check_animation(anim_path):
    print(f"Checking {anim_path}")
    with open(anim_path, 'r') as f:
        data = json.load(f)
        animations = data.get('animations', {})
        for name, anim in animations.items():
            length = anim.get('animation_length')
            bones = anim.get('bones', {})
            for bone_name, channels in bones.items():
                for channel, frames in channels.items():
                    if isinstance(frames, dict):
                        for time, val in frames.items():
                            try:
                                t = float(time)
                                if t > length:
                                    print(f"  [ERROR] {name}: {bone_name} {channel} keyframe {time} > {length}")
                            except ValueError:
                                # 跳过 vector 或其他非数字键
                                continue

if __name__ == "__main__":
    check_animation("src/main/resources/assets/galboss/animations/beatrice.animation.json")
