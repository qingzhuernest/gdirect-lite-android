# Windows visual system

Mode: Operate. Refinement of the existing blue Drive-like WPF surface; original Android DESIGN.md is unchanged.

Font: Microsoft YaHei UI, body14, product title26, section18. Window width1000, height bounded by Windows work area, minimum800×560. Main margin28; grouped gaps12/14/18/20. Buttons minimum42 high, padding16×10, radius8, text labels and visible keyboard focus. Native text fields preserve selection/IME behavior.

Roles: application background #F4F7FC; file surface #FFFFFF; main text #1F2937; secondary #42536B/#52627A; primary #0B57D0 with white text, hover #174EA6; secondary controls #EDF3FA with #23334B; hover #DDE9FA; input border #C9D6E6; file header #F4F7FB; row hover #F0F6FF; selection #DCEAFF with #123A70; separators #EEF2F7. Disabled controls use #F1F3F6/#727D8B rather than white-on-gray. Table header42 and row46, cell padding14×8, selected row retains a text label and native keyboard selection.

Account, public-share download, file search/list and task status remain in the same order. Existing controls/events and read-only capability remain. Link download is visibly labelled as login-free; authentication evidence is never described as actual transfer success. Do not add fabricated history, statistics, upload or write capabilities.

Initial rendering receives a bounded native screenshot review. Authenticated content, large-font/high-contrast and actual account/network scenarios retain explicit acceptance limits.
