# ⚔️ CyayoGuildWar - Professional Guild Territory War System

"Bangun kerajaan guild-mu, taklukkan wilayah-wilayah strategis, pertahankan dari serangan musuh, dan pimpin Season Rating teratas untuk membuktikan kehebatan guild-mu."

```text
=========================================
             QUICK TUTORIAL
=========================================
```

### 1. Sistem Perebutan Wilayah (Territory Capture)
- Deklarasikan perang terhadap wilayah target menggunakan koordinat banner wilayah yang telah ditentukan.
- Saat perang dimulai, partisipan terpilih (Attacker & Defender) akan otomatis diteleportasi ke arena pertempuran secara instan.
- Guild yang memenangkan pertempuran akan menguasai wilayah tersebut dan warna banner fisik di dunia Minecraft akan berubah secara dinamis mengikuti lambang guild pemenang.

### 2. Season & Rating (SR Points)
- Setiap kemenangan perang (baik merebut teritori maupun sukses bertahan) akan memberikan poin **Season Rating (SR)**.
- Setiap beberapa menit (interval dapat diatur), guild yang menguasai wilayah akan secara otomatis mendapatkan poin SR tambahan secara periodik.
- Season ditutup secara otomatis atau manual oleh Admin, membagikan hadiah ke Top Guilds, dan memulai masa damai (Peace Period) sebelum season baru dimulai.

### 3. War Vault & Buffs
- Teritori yang dikuasai memberikan efek **Potion Buffs** (seperti Haste, Speed, Regeneration) dan **Grinding XP Buffs** (AuraSkills) kepada seluruh anggota guild selama berada di dalam wilayah teritori.
- Reward berkala (berupa MMOItems atau eksekusi konsol command) akan otomatis dikirimkan ke dalam **War Vault** bersama milik guild yang menguasai teritori tersebut.

```text
=========================================
           COMMANDS & PERMS
=========================================

USER COMMANDS:
/war                         > Buka menu statistik utama, daftar wilayah, log, dan peringkat guild.
/war info                    > Membuka menu info daftar wilayah teritori.
/war banner                  > Membuka menu kostumisasi banner Guild.
/war stats                   > Membuka menu preview statistik & log guild sendiri.
/war attack                  > Menyatakan perang / serang wilayah teritori saat ini.
/war topterr                 > Membuka menu peringkat guild berdasarkan jumlah wilayah kekuasaan.
/war topsr                   > Membuka menu peringkat guild berdasarkan poin Season Rating (SR).
/war allterr                 > Membuka menu peta status kepemilikan seluruh wilayah teritori.
/war spectate                > Nonton (spectate) pertempuran Guild War yang sedang berlangsung.
/war spectate <player>       > Nonton dengan mengikuti kamera player target.
/war toggle                  > Mengaktifkan / menonaktifkan keikutsertaan war untuk diri sendiri.
/war vault                   > Membuka brankas (War Vault) milik guild sendiri.

ADMIN COMMANDS:
/waradmin reload             > Muat ulang seluruh konfigurasi, teritori, dan GUI.
/waradmin resetsr            > Reset seluruh poin SR guild saat ini secara instan.
/waradmin resetseason        > Paksa akhir season berjalan, bagikan hadiah, dan mulai masa damai.
/waradmin setseason <season> > Atur nomor season yang sedang berjalan saat ini.
/waradmin setbanner <wilayah>> Atur lokasi banner fisik wilayah target di lokasi berdiri saat ini.
/waradmin setowner <wilayah> <guild> > Atur kepemilikan teritori ke guild tertentu secara paksa.
/waradmin vault <guild>      > Buka War Vault milik guild mana pun.
/waradmin give <guild>       > Kirim item di tangan langsung ke War Vault guild target.
/waradmin testbuff <wilayah> > Pemicu (test run) pembagian periodic rewards teritori saat ini.

PERMISSIONS:
cyayoguildwar.use            > Izin dasar menggunakan seluruh menu user /war dan /war vault.
cyayoguildwar.admin          > Akses penuh ke seluruh fitur administratif (/waradmin).
cyayoguildwar.admin.bypass   > Mengabaikan larangan command & bypass proteksi saat perang berlangsung.
```

```text
=========================================
          PLACEHOLDERAPI LIST
=========================================

Gunakan placeholder berikut untuk keperluan papan skor (Scoreboard), papan melayang (Hologram), Chat, dll:

INFORMASI PERSONAL / GUILD SENDIRI:
%war_guild_sr%               > Menampilkan poin Season Rating (SR) guild milik pemain saat ini.
%war_current_season%         > Menampilkan nomor season yang sedang berjalan saat ini.
%war_luck%                   > Menampilkan bonus Luck tambahan saat pemain berada di teritori guildnya sendiri.
%war_top_guild_<1-10>%       > Menampilkan nama guild di peringkat 1 hingga 10 (cth: %war_top_guild_1% untuk Juara 1).
%war_top_sr_<1-10>%          > Menampilkan total poin SR milik guild di peringkat 1 hingga 10 (cth: %war_top_sr_1%).
%war_<namaregion>%           > Menampilkan status & pemilik teritori saat ini (cth: %war_luminavillage%).
```

### 🚀 Advanced Features
- 🚩 **Dynamic Banner Color** - Mengubah pola dan warna banner fisik di server Minecraft secara real-time mengikuti banner kustom guild yang menang.
- 📦 **Split Config System** - Pengaturan GUI yang terpisah memudahkan modifikasi visual menu statistik, detail wilayah, vault, dan log.
- 🔗 **CyayoPvP Integration** - Otomatis mematikan & memulihkan Hunt Mode dari `CyayoPvP` saat perang dimulai untuk mencegah konflik efek glow dan kelebihan bonus luck.
- 🛡️ **Duel Protection** - Mencegah pemain yang sedang aktif berduel di arena PvP ikut ter-teleport secara tidak sengaja ke Guild War.
- 📢 **Flexible Discord Webhook** - Dukungan mode **Embed** premium atau mode **Text** (untuk ping/mentions) yang dikirimkan secara asinkron ke server Discord saat war dimulai, menang, atau gagal.

---

Made with ❤️ by **Cyayo**
